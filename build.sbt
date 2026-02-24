ThisBuild / organization := "com.pcpitman"
ThisBuild / version := {
  import scala.sys.process._
  val branch = "git rev-parse --abbrev-ref HEAD".!!.trim
  val commitTag = scala.util.Try("git describe --tags --exact-match HEAD".!!.trim).toOption
  val lastTag = scala.util.Try("git describe --tags --abbrev=0".!!.trim).toOption
  val commit = "git rev-parse HEAD".!!.trim.take(8)
  val timestamp = {
    val fmt = new java.text.SimpleDateFormat("yyyyMMddHHmmss")
    fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))
    fmt.format(new java.util.Date())
  }
  val base = lastTag.getOrElse("0.0.0")
  if (branch == "main" && commitTag.isDefined) commitTag.get
  else if (branch == "main") s"${base}-${timestamp}-${commit}"
  else s"${base}-${branch}-${timestamp}-${commit}"
}

lazy val root = (project in file("."))
  .aggregate(dynamodb, ses, s3)
  .settings(
    name := "aws-mock",
    publish / skip := true
  )

lazy val commonSettings = Seq(
  scalaVersion := "3.8.1",
  crossScalaVersions := Seq("2.13.18", "3.8.1"),
  scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature"),
  libraryDependencies ++= Seq(
    Dependencies.scalaLogging,
    Dependencies.slf4jApi,
    Dependencies.log4jApi       % Test,
    Dependencies.log4jCore      % Test,
    Dependencies.log4jSlf4j     % Test,
    Dependencies.munit          % Test
  ),
  testFrameworks += new TestFramework("munit.Framework")
)

lazy val dynamodb = (project in file("dynamodb"))
  .settings(commonSettings)
  .settings(
    name := "aws-mock-dynamodb",
    libraryDependencies ++= Seq(
      Dependencies.aws2DynamoDB
    )
  )

lazy val ses = (project in file("ses"))
  .settings(commonSettings)
  .settings(
    name := "aws-mock-ses",
    libraryDependencies ++= Seq(
      Dependencies.aws2SES
    )
  )

lazy val s3 = (project in file("s3"))
  .settings(commonSettings)
  .settings(
    name := "aws-mock-s3",
    libraryDependencies ++= Seq(
      Dependencies.aws2S3
    )
  )
