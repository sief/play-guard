name := """play-guard"""

organization := """com.digitaltangible"""

version := "3.0.0-SNAPSHOT"

scalaVersion := "2.13.12"

crossScalaVersions := Seq("2.13.12", "3.3.1")

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalacOptions ++= Seq(
  "-feature",
  "-Xfatal-warnings"
)

libraryDependencies ++= Seq(
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.0" % Test,
  "org.scalatestplus" %% "scalacheck-1-17" % "3.2.17.0" % Test
)



publishMavenStyle := true

Test / publishArtifact := false

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (version.value endsWith "SNAPSHOT")
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
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
