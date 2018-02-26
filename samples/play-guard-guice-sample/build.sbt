import play.sbt.Play.autoImport._
import sbt._

name := """play-guard-guice-sample"""

version := "1.1-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  guice,
  "com.digitaltangible" %% "play-guard" % "2.2.0"
)

