name := """play-guard-sample"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.5"

libraryDependencies ++= Seq(
  "com.digitaltangible" %% "play-guard" % "1.4.1"
)
