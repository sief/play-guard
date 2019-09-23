import play.sbt.Play.autoImport._
import sbt._

name := """play-guard-guice-sample"""

version := "1.1-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.0"

libraryDependencies ++= Seq(
  guice,
  "com.digitaltangible" %% "play-guard" % "2.4.0"
)

