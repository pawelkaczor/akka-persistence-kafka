package com.github.j5ik2o.akka.persistence.kafka.serialization

import akka.actor.ExtendedActorSystem
import akka.persistence.PersistentRepr
import akka.serialization.{ AsyncSerializerWithStringManifest, SerializationExtension }
import com.github.j5ik2o.akka.persistence.kafka.journal.JournalRow
import com.github.j5ik2o.akka.persistence.kafka.protocol.JournalFormat
import com.google.protobuf.ByteString
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

import scala.collection.immutable
import scala.concurrent.Future

object JournalAkkaSerializer {
  val Identifier = 15443
}

class JournalAkkaSerializer(system: ExtendedActorSystem) extends AsyncSerializerWithStringManifest(system) {
  import system.dispatcher
  private val config: Config = system.settings.config.getConfig("j5ik2o.kafka-journal")

  private val logger                       = LoggerFactory.getLogger(getClass)
  override def identifier: Int             = JournalAkkaSerializer.Identifier
  override def manifest(o: AnyRef): String = o.getClass.getName

  private val byteArrayFilter: EncryptionFilter = {
    val className = config.getString("encryption-filter-class-name")
    system.dynamicAccess
      .createInstanceFor[EncryptionFilter](className, immutable.Seq.empty)
      .getOrElse(throw new ClassNotFoundException(className))
  }

  override def toBinaryAsync(o: AnyRef): Future[Array[Byte]] = {
    o match {
      case journal: JournalRow =>
        logger.debug("toBinary:journal = {}", journal)
        Future {
          val serializer = SerializationExtension(system).findSerializerFor(journal.persistentRepr)
          JournalFormat(
            persistenceId = journal.persistenceId.asString,
            sequenceNumber = journal.sequenceNumber.value,
            persistentRepr = ByteString.copyFrom(serializer.toBinary(journal.persistentRepr)),
            deleted = journal.deleted,
            manifest = journal.manifest,
            timestamp = journal.timestamp,
            writerUuid = journal.writerUuid,
            tags = journal.tags
          ).toByteArray
        }.flatMap(
          byteArrayFilter.encrypt(journal.persistenceId, _, Map("tags" -> journal.tags))
        )
      case _ => Future.failed(new IllegalArgumentException(s"Can't serialize object of type ${o.getClass}"))
    }
  }

  override def fromBinaryAsync(bytes: Array[Byte], manifest: String): Future[AnyRef] =
    byteArrayFilter.decrypt(bytes).map { decryptedBytes =>
      val journalFormat = JournalFormat.parseFrom(decryptedBytes)
      val result = JournalRow(
        persistentRepr = SerializationExtension(system)
          .deserialize(
            journalFormat.persistentRepr.toByteArray,
            classOf[PersistentRepr]
          )
          .get,
        tags = journalFormat.tags.toList
      )
      logger.debug("fromBinary:journal = {}", result)
      result
    }

}
