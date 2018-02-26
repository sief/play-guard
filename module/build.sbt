name := """play-guard"""

organization := """com.digitaltangible"""

version := "2.2.0"

scalaVersion := "2.12.4"

crossScalaVersions := Seq("2.12.4", "2.11.12")


lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalacOptions ++= Seq(
  "-feature",
  "-language:higherKinds"
)

libraryDependencies ++= Seq(
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % "test",
  "org.scalacheck" %% "scalacheck" % "1.13.5" % "test"
)



pomIncludeRepository := { _ => false }

publishMavenStyle := true

publishArtifact in Test := false

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

licenses := Seq("Apache 2" -> url("http://www.apache.org/licenses/LICENSE-2.0"))

scmInfo := Some(
  ScmInfo(
    url("https://github.com/sief/play-guard"),
    "git@github.com:sief/play-guard.git"
  )
)

developers := List(
  Developer(
    id    = "sief",
    name  = "Simon Effing",
    email = "mail@simoneffing.com",
    url   = url("http://simoneffing.com")
  )
)
