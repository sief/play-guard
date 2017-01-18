name := """play-guard-guice-sample"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"


libraryDependencies ++= Seq(
  "com.digitaltangible" %% "play-guard" % "2.0.0-SNAPSHOT"
)

