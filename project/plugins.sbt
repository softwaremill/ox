val sbtSoftwareMillVersion = "3.0.2"
addSbtPlugin("com.softwaremill.sbt-softwaremill" % "sbt-softwaremill-common" % sbtSoftwareMillVersion)
addSbtPlugin("com.softwaremill.sbt-softwaremill" % "sbt-softwaremill-publish" % sbtSoftwareMillVersion)
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.9.2")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.2.1")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.13")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.5.0")
