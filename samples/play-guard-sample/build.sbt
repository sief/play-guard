name := """play-guard-sample"""

version := "1.1-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "3.3.1"

libraryDependencies ++= Seq(
  "com.digitaltangible" %% "play-guard" % "3.0.0"
)
