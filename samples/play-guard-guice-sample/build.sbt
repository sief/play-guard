import play.sbt.Play.autoImport._
import sbt._

name := """play-guard-guice-sample"""

version := "1.1-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.1"

libraryDependencies ++= Seq(
  guice,
  "com.digitaltangible" %% "play-guard" % "2.5.0"
)

