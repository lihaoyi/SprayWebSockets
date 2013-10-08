import sbt._
import Keys._

object Build extends sbt.Build{

  lazy val proj = Project(
    "WebSockets",
    file("."),
    settings =
      Defaults.defaultSettings ++ Seq(
      organization  := "com.example",
      version       := "0.1",
      scalaVersion  := "2.10.2",

      resolvers ++= Seq(
        "typesafe repo"      at "http://repo.typesafe.com/typesafe/releases/",
        "spray"              at "http://repo.spray.io",
        "spray nightly"      at "http://nightlies.spray.io/"
      ),
      libraryDependencies ++= Seq(
        "io.spray"            %   "spray-can"     % "1.2-M8",
        "io.spray"            %   "spray-routing" % "1.2-M8",
        "io.spray"            %   "spray-testkit" % "1.2-M8" % "test",
        "com.typesafe.akka"   %%  "akka-actor"    % "2.2.0-RC1",
        "com.typesafe.akka"   %%  "akka-testkit"  % "2.2.0-RC1" % "test",
        "org.scalatest" % "scalatest_2.10" % "2.0.RC1" % "test",
        "org.java-websocket" % "Java-WebSocket" % "1.3.0"
      )
    )
  )
}