resolvers ++= Seq(
  "Sonatype OSS Snapshot Repository" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Sonatype OSS Release Repository" at "https://oss.sonatype.org/content/repositories/releases/"
)

libraryDependencies ++= {
  Seq(
    "com.thesamet.scalapb" %% "compilerplugin" % "0.10.10"
  )
}

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.34")

addSbtPlugin("com.github.sbt" % "sbt-release" % "1.0.15")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.5")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.2")
