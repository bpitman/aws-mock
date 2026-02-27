package software.amazon.awssdk.services.s3

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.util.concurrent.TimeUnit

import munit.FunSuite

import software.amazon.awssdk.core.async.*
import software.amazon.awssdk.services.s3.model.*

class S3Suite extends FunSuite {

  def newClient(): S3AsyncClient = MockS3AsyncClient.newProxy(Clock.systemUTC())

  test("create client") {
    val s3 = newClient()
    assertEquals(s3.serviceName(), "s3")
    intercept[UnsupportedOperationException] {
      s3.listBuckets()
    }
  }

  test("create bucket") {
    val s3 = newClient()
    val response = s3.createBucket(
      CreateBucketRequest.builder().bucket("test-bucket").build()
    ).get(5, TimeUnit.SECONDS)
    assertEquals(response.location(), "us-west-2")
  }

  test("put and get object") {
    val s3 = newClient()
    val bucket = "test-bucket"
    val key = "test/key"
    val content = "content for key in bucket"

    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
      .get(5, TimeUnit.SECONDS)

    val putReq = PutObjectRequest.builder()
      .bucket(bucket)
      .key(key)
      .contentLength(content.length.toLong)
      .build()
    s3.putObject(putReq, AsyncRequestBody.fromString(content))
      .get(5, TimeUnit.SECONDS)

    val getReq = GetObjectRequest.builder()
      .bucket(bucket)
      .key(key)
      .build()
    val bytes = s3.getObject(getReq, AsyncResponseTransformer.toBytes[GetObjectResponse])
      .get(5, TimeUnit.SECONDS)
    assertEquals(new String(bytes.asByteArray(), UTF_8), content)
  }
}
