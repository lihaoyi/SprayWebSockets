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
      scalaVersion  := "2.10.0",

      resolvers ++= Seq(
        "typesafe repo"      at "http://repo.typesafe.com/typesafe/releases/",
        "spray nightly"      at "http://nightlies.spray.io/"
      ),
      libraryDependencies ++= Seq(
        "io.spray"                %   "spray-can"     % "1.1-20130129",
        "com.typesafe.akka"       %%  "akka-actor"    % "2.1.0",
        "com.typesafe.akka"       %%  "akka-testkit"    % "2.1.0" % "test",
        "io.spray"                %%  "spray-json"    % "1.2.3",
        "io.spray"                %  "spray-httpx"    % "1.1-20130129",
        "io.spray"                %  "spray-client"    % "1.1-20130129",
        "org.scalatest" % "scalatest_2.10.0" % "2.0.M5" % "test",
        "org.seleniumhq.selenium" % "selenium-java" % "2.28.0" % "test",
        "org.scalacheck" %% "scalacheck" % "1.10.0" % "test"
      )
    )
  )
}