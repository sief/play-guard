name := """play-guard-sample"""

version := "1.1-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.1"

libraryDependencies ++= Seq(
  "com.digitaltangible" %% "play-guard" % "2.4.0"

)
