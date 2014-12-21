name := """play-guard-sample"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.4"

libraryDependencies ++= Seq(
  "com.sief" %% "play-guard" % "1.3-SNAPSHOT"
)

resolvers ++= Seq("Local" at "file:///Users/simoneffing/.ivy2/local")
