package software.amazon.awssdk.services.kms

import java.time.Clock
import java.util.concurrent.TimeUnit

import munit.FunSuite

import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kms.model._

class KmsSuite extends FunSuite {

  def newClient(): KmsAsyncClient = MockKmsAsyncClient.newProxy(Clock.systemUTC())

  def createKey(kms: KmsAsyncClient): String = {
    val response = kms.createKey(
      CreateKeyRequest.builder().description("test key").build()
    ).get(5, TimeUnit.SECONDS)
    response.keyMetadata.keyId
  }

  test("create client") {
    val kms = newClient()
    assertEquals(kms.serviceName(), "kms")
    intercept[java.lang.UnsupportedOperationException] {
      kms.listKeys()
    }
  }

  test("create and describe key") {
    val kms = newClient()
    val keyId = createKey(kms)

    val response = kms.describeKey(
      DescribeKeyRequest.builder().keyId(keyId).build()
    ).get(5, TimeUnit.SECONDS)
    assertEquals(response.keyMetadata.keyId, keyId)
    assertEquals(response.keyMetadata.description, "test key")
    assert(response.keyMetadata.enabled)
  }

  test("encrypt and decrypt") {
    val kms = newClient()
    val keyId = createKey(kms)
    val plaintext = "hello world"

    val encResponse = kms.encrypt(
      EncryptRequest.builder()
        .keyId(keyId)
        .plaintext(SdkBytes.fromUtf8String(plaintext))
        .build()
    ).get(5, TimeUnit.SECONDS)
    assertNotEquals(encResponse.ciphertextBlob.asByteArray().toSeq, plaintext.getBytes.toSeq)

    val decResponse = kms.decrypt(
      DecryptRequest.builder()
        .keyId(keyId)
        .ciphertextBlob(encResponse.ciphertextBlob)
        .build()
    ).get(5, TimeUnit.SECONDS)
    assertEquals(decResponse.plaintext.asUtf8String, plaintext)
  }

  test("create alias and use it") {
    val kms = newClient()
    val keyId = createKey(kms)

    kms.createAlias(
      CreateAliasRequest.builder()
        .aliasName("alias/my-key")
        .targetKeyId(keyId)
        .build()
    ).get(5, TimeUnit.SECONDS)

    val response = kms.describeKey(
      DescribeKeyRequest.builder().keyId("alias/my-key").build()
    ).get(5, TimeUnit.SECONDS)
    assertEquals(response.keyMetadata.keyId, keyId)
  }

  test("generate data key") {
    val kms = newClient()
    val keyId = createKey(kms)

    val response = kms.generateDataKey(
      GenerateDataKeyRequest.builder()
        .keyId(keyId)
        .keySpec(DataKeySpec.AES_256)
        .build()
    ).get(5, TimeUnit.SECONDS)
    assertEquals(response.plaintext.asByteArray().length, 32)
    assertNotEquals(
      response.plaintext.asByteArray().toSeq,
      response.ciphertextBlob.asByteArray().toSeq
    )

    // decrypt the encrypted data key
    val decResponse = kms.decrypt(
      DecryptRequest.builder()
        .keyId(keyId)
        .ciphertextBlob(response.ciphertextBlob)
        .build()
    ).get(5, TimeUnit.SECONDS)
    assertEquals(decResponse.plaintext.asByteArray().toSeq, response.plaintext.asByteArray().toSeq)
  }

  test("schedule key deletion") {
    val kms = newClient()
    val keyId = createKey(kms)

    val response = kms.scheduleKeyDeletion(
      ScheduleKeyDeletionRequest.builder()
        .keyId(keyId)
        .pendingWindowInDays(7)
        .build()
    ).get(5, TimeUnit.SECONDS)
    assertEquals(response.keyId, keyId)

    // key should be disabled after scheduling deletion
    val ex = intercept[java.util.concurrent.ExecutionException] {
      kms.encrypt(
        EncryptRequest.builder()
          .keyId(keyId)
          .plaintext(SdkBytes.fromUtf8String("test"))
          .build()
      ).get(5, TimeUnit.SECONDS)
    }
    assert(ex.getCause.isInstanceOf[DisabledException])
  }

  test("describe nonexistent key") {
    val kms = newClient()
    val ex = intercept[java.util.concurrent.ExecutionException] {
      kms.describeKey(
        DescribeKeyRequest.builder().keyId("nonexistent").build()
      ).get(5, TimeUnit.SECONDS)
    }
    assert(ex.getCause.isInstanceOf[NotFoundException])
  }
}
