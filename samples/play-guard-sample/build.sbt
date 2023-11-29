name := """play-guard-sample"""

version := "1.1-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  "com.digitaltangible" %% "play-guard" % "3.0.0-SNAPSHOT"
)
