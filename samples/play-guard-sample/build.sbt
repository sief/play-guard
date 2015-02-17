name := """play-guard-sample"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.5"

libraryDependencies ++= Seq(
  "com.sief" %% "play-guard" % "1.4.0"
)

resolvers ++= Seq("Local" at "file:///Users/simoneffing/.ivy2/local")
