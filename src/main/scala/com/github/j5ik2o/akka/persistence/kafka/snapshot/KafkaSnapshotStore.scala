package com.github.j5ik2o.akka.persistence.kafka.snapshot

import akka.actor.{ ActorSystem, DynamicAccess, ExtendedActorSystem }
import akka.kafka.scaladsl.{ Consumer, Producer }
import akka.kafka.{ ConsumerSettings, ProducerSettings, Subscriptions }
import akka.persistence.snapshot.SnapshotStore
import akka.persistence.{ SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria }
import akka.serialization.SerializationExtension
import akka.stream.scaladsl.{ Sink, Source }
import com.github.j5ik2o.akka.persistence.kafka.journal.{ JournalSequence, PersistenceId }
import com.github.j5ik2o.akka.persistence.kafka.resolver.{ KafkaPartitionResolver, KafkaTopicResolver }
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{ ByteArrayDeserializer, ByteArraySerializer, StringDeserializer, StringSerializer }

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import scala.jdk.CollectionConverters._

object KafkaSnapshotStore {

  type RangeDeletions = Map[String, SnapshotSelectionCriteria]
  type SingleDeletions = Map[String, List[SnapshotMetadata]]

}

class KafkaSnapshotStore(config: Config) extends SnapshotStore {
  import KafkaSnapshotStore._
  import context.dispatcher
  implicit val system: ActorSystem = context.system
  val ignoreOrphan = false
  protected val producerSettings: ProducerSettings[String, Array[Byte]] =
    ProducerSettings(producerConfig, new StringSerializer, new ByteArraySerializer)
  protected val producer = producerSettings.createKafkaProducer()
  protected val consumerSettings: ConsumerSettings[String, Array[Byte]] =
    ConsumerSettings(consumerConfig, new StringDeserializer, new ByteArrayDeserializer)
  protected val journalTopicResolver: KafkaTopicResolver = {
    val className = config
      .as[String]("topic-resolver-class-name")
    dynamicAccess
      .createInstanceFor[KafkaTopicResolver](
        className,
        immutable.Seq(classOf[Config] -> config)
      )
      .getOrElse(throw new ClassNotFoundException(className))
  }
  protected val journalPartitionResolver: KafkaPartitionResolver = {
    val className = config
      .as[String]("partition-resolver-class-name")
    dynamicAccess
      .createInstanceFor[KafkaPartitionResolver](
        className,
        immutable.Seq(classOf[Config] -> config)
      )
      .getOrElse(throw new ClassNotFoundException(className))
  }
  protected val journalSequence = new JournalSequence(
    consumerSettings,
    config.as[String]("topic-prefix"),
    journalTopicResolver,
    journalPartitionResolver
  )
  private val producerConfig = config.getConfig("producer")
  private val consumerConfig = config.getConfig("consumer")
  private val serialization = SerializationExtension(context.system)
  private val dynamicAccess: DynamicAccess = system.asInstanceOf[ExtendedActorSystem].dynamicAccess
  private var rangeDeletions: RangeDeletions = Map.empty.withDefaultValue(SnapshotSelectionCriteria.None)
  private var singleDeletions: SingleDeletions = Map.empty.withDefaultValue(Nil)

  override def postStop(): Unit = {
    producer.close()
    super.postStop()
  }

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    log.debug(s"saveAsync($metadata, $snapshot): start")
    Source
      .single(SnapshotRow(metadata, snapshot))
      .flatMapConcat { snapshot =>
        serialization.serialize(snapshot) match {
          case Failure(ex)    => Source.failed(ex)
          case Success(bytes) => Source.single(bytes)
        }
      }
      .map { snapshotBytes =>
        new ProducerRecord[String, Array[Byte]](
          resolveTopic(PersistenceId(metadata.persistenceId)),
          resolvePartition(PersistenceId(metadata.persistenceId)),
          metadata.persistenceId,
          snapshotBytes
        )
      }
      .runWith(Producer.plainSink(producerSettings))
      .map(_ => ())
  }

  protected def resolvePartition(persistenceId: PersistenceId): Int = {
    val partitionSize = resolvePartitionSize(persistenceId)
    journalPartitionResolver.resolve(partitionSize, persistenceId).value
  }

  protected def resolvePartitionSize(persistenceId: PersistenceId): Int = {
    val topic = resolveTopic(persistenceId)
    producer.partitionsFor(topic).asScala.size
  }

  protected def resolveTopic(persistenceId: PersistenceId): String =
    config.as[String]("topic-prefix") + journalTopicResolver.resolve(persistenceId).asString

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    val singleDeletions = this.singleDeletions
    val rangeDeletions = this.rangeDeletions

    def matcher(snapshot: SnapshotRow): Boolean =
      snapshot.matches(criteria) &&
      !snapshot.matches(rangeDeletions(persistenceId)) &&
      !singleDeletions(persistenceId).contains(snapshot.metadata) &&
      !singleDeletions(persistenceId)
        .filter(_.timestamp == 0L)
        .map(_.sequenceNr)
        .contains(snapshot.metadata.sequenceNr)

    // if timestamp was unset on delete, matches only on same sequence nr
    load(PersistenceId(persistenceId), matcher).map(sOpt => sOpt.map { s => SelectedSnapshot(s.metadata, s.payload) })
  }

  private def load(persistenceId: PersistenceId, matcher: SnapshotRow => Boolean): Future[Option[SnapshotRow]] = {
    val offset = journalSequence.readHighestSequenceNr(persistenceId)
    def load0(persistenceId: PersistenceId, offset: Long): Future[Option[SnapshotRow]] =
      if (offset < 0) Future.successful(None)
      else {
        snapshot(persistenceId, offset).flatMap { s =>
          if (matcher(s)) Future.successful(Some(s)) else load0(persistenceId, offset - 1)
        }
      }
    load0(persistenceId, offset - 1)
  }

  private def snapshot(persistenceId: PersistenceId, offset: Long): Future[SnapshotRow] = {
    Consumer
      .plainSource(
        consumerSettings,
        Subscriptions.assignmentWithOffset(
          new TopicPartition(
            resolveTopic(persistenceId),
            resolvePartition(persistenceId)
          ) -> offset
        )
      )
      .take(1)
      .map { record => serialization.deserialize(record.value(), classOf[SnapshotRow]).get }
      .runWith(Sink.head)
  }

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = Future.successful {
    singleDeletions.get(metadata.persistenceId) match {
      case Some(dels) => singleDeletions += (metadata.persistenceId -> (metadata :: dels))
      case None       => singleDeletions += (metadata.persistenceId -> List(metadata))
    }
  }

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] =
    Future.successful {
      rangeDeletions += (persistenceId -> criteria)
    }
}
