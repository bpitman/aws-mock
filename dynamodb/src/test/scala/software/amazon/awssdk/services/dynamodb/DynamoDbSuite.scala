package software.amazon.awssdk.services.dynamodb

import scala.jdk.CollectionConverters._

import java.time.Clock
import java.util.concurrent.TimeUnit

import munit.FunSuite

import software.amazon.awssdk.services.dynamodb.model._

class DynamoDbSuite extends FunSuite {

  def newClient(): DynamoDbAsyncClient = MockDynamoDbAsyncClient.newProxy(Clock.systemUTC())

  def createTable(dynamoDb: DynamoDbAsyncClient, name: String): Unit = {
    val request = CreateTableRequest.builder()
      .tableName(name)
      .keySchema(KeySchemaElement.builder().attributeName("id").keyType("HASH").build())
      .attributeDefinitions(
        AttributeDefinition.builder().attributeName("id").attributeType("S").build()
      )
      .provisionedThroughput(
        ProvisionedThroughput.builder()
          .readCapacityUnits(1L)
          .writeCapacityUnits(1L)
          .build()
      )
      .build()
    dynamoDb.createTable(request).get(5, TimeUnit.SECONDS)
  }

  test("simple") {
    val dynamoDb = newClient()
    assertEquals(dynamoDb.serviceName(), "dynamodb")
    intercept[UnsupportedOperationException] {
      dynamoDb.describeLimits()
    }
  }

  test("create and describe table") {
    val dynamoDb = newClient()
    createTable(dynamoDb, "test-table")

    val request = DescribeTableRequest.builder()
      .tableName("test-table")
      .build()
    val response = dynamoDb.describeTable(request).get(5, TimeUnit.SECONDS)
    assertEquals(response.table.tableName, "test-table")
  }

  test("put and get item") {
    val dynamoDb = newClient()
    createTable(dynamoDb, "test-table")

    val put = PutItemRequest.builder()
      .tableName("test-table")
      .item(Map(
        "id" -> MockUtil.sAttr("id0"),
        "other" -> MockUtil.nAttr(5)
      ).asJava)
      .build()
    dynamoDb.putItem(put).get(5, TimeUnit.SECONDS)

    val get = GetItemRequest.builder()
      .tableName("test-table")
      .key(Map(
        "id" -> MockUtil.sAttr("id0")
      ).asJava)
      .build()
    val response = dynamoDb.getItem(get).get(5, TimeUnit.SECONDS)
    assertEquals(response.item.get("other").n.toLong, 5L)
  }

  test("transact write items") {
    val dynamoDb = newClient()
    createTable(dynamoDb, "test1")
    createTable(dynamoDb, "test2")

    val put1 = PutItemRequest.builder()
      .tableName("test1")
      .item(Map(
        "id" -> MockUtil.sAttr("id1"),
        "foo" -> MockUtil.boolAttr(true)
      ).asJava)
      .build()
    dynamoDb.putItem(put1).get(5, TimeUnit.SECONDS)

    val put2 = PutItemRequest.builder()
      .tableName("test2")
      .item(Map(
        "id" -> MockUtil.sAttr("id2"),
        "bar" -> MockUtil.nAttr(15)
      ).asJava)
      .build()
    dynamoDb.putItem(put2).get(5, TimeUnit.SECONDS)

    val get1 = GetItemRequest.builder()
      .tableName("test1")
      .key(Map("id" -> MockUtil.sAttr("id1")).asJava)
      .build()

    val get2 = GetItemRequest.builder()
      .tableName("test2")
      .key(Map("id" -> MockUtil.sAttr("id2")).asJava)
      .build()

    val r1 = dynamoDb.getItem(get1).get(5, TimeUnit.SECONDS)
    assertEquals(r1.item.get("foo").bool, java.lang.Boolean.TRUE)

    val r2 = dynamoDb.getItem(get2).get(5, TimeUnit.SECONDS)
    assertEquals(r2.item.get("bar").n.toLong, 15L)

    val trans = TransactWriteItemsRequest.builder()
      .transactItems(
        TransactWriteItem.builder()
        .update(
          Update.builder()
          .tableName("test1")
          .key(Map("id" -> MockUtil.sAttr("id1")).asJava)
          .conditionExpression("foo = :true")
          .updateExpression("set foo = :false")
          .expressionAttributeValues(
            Map(
              ":true" -> MockUtil.boolAttr(true),
              ":false" -> MockUtil.boolAttr(false),
            ).asJava
          )
          .build()
        )
        .build(),
        TransactWriteItem.builder()
        .update(
          Update.builder()
           .tableName("test2")
           .key(Map("id" -> MockUtil.sAttr("id2")).asJava)
           .conditionExpression("bar >= :value")
           .updateExpression("set bar = bar - :value")
           .expressionAttributeValues(
             Map(
               ":value" -> MockUtil.nAttr(5)
             ).asJava
           )
           .build()
        )
        .build()
      )
      .build()
    dynamoDb.transactWriteItems(trans).get(5, TimeUnit.SECONDS)

    val r3 = dynamoDb.getItem(get1).get(5, TimeUnit.SECONDS)
    assertEquals(r3.item.get("foo").bool, java.lang.Boolean.FALSE)

    val r4 = dynamoDb.getItem(get2).get(5, TimeUnit.SECONDS)
    assertEquals(r4.item.get("bar").n.toLong, 10L)
  }
}
