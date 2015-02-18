name := """play-guard"""

organization := """com.digitaltangible"""

version := "1.4.1"

scalaVersion := "2.11.5"

crossScalaVersions := Seq("2.10.4", "2.11.5")

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "2.2.1" % "test",
  "org.scalacheck" %% "scalacheck" % "1.11.6" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.4" % "test"
)


publishMavenStyle := true

publishArtifact in Test := false

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (version.value endsWith "SNAPSHOT")
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

pomExtra := <url>https://github.com/sief/play-guard</url>
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:sief/play-guard.git</url>
    <connection>scm:git:git@github.com:sief/play-guard.git</connection>
  </scm>
  <developers>
    <developer>
      <id>sief</id>
      <name>Simon Effing</name>
      <url>https://www.linkedin.com/in/simoneffing</url>
    </developer>
  </developers>