name := """play-guard"""

organization := """com.sief"""

version := "1.4.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.5"

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "2.2.1" % "test",
  "org.scalacheck" %% "scalacheck" % "1.11.6" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.4" % "test"
)
