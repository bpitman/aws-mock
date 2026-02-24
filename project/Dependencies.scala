import sbt._

// format: off

object Dependencies {
  object Versions {
    val aws2      = "2.41.22"
    val log4j     = "2.25.3"
    val slf4j     = "2.0.17"
  }

  import Versions._

  val aws2DynamoDB   = "software.amazon.awssdk"       % "dynamodb"           % aws2
  val aws2KMS        = "software.amazon.awssdk"       % "kms"                % aws2
  val aws2S3         = "software.amazon.awssdk"       % "s3"                 % aws2
  val aws2SES        = "software.amazon.awssdk"       % "ses"                % aws2
  val log4jApi       = "org.apache.logging.log4j"     % "log4j-api"          % log4j
  val log4jCore      = "org.apache.logging.log4j"     % "log4j-core"         % log4j
  val log4jSlf4j     = "org.apache.logging.log4j"     % "log4j-slf4j2-impl"  % log4j
  val scalaLogging   = "com.typesafe.scala-logging" %% "scala-logging"      % "3.9.6"
  val slf4jApi       = "org.slf4j"                    % "slf4j-api"          % slf4j

  // Test
  val munit          = "org.scalameta"              %% "munit"              % "1.2.2"
}

// format: on
