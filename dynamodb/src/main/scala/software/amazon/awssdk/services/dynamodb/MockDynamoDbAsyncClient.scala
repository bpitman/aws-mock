package software.amazon.awssdk.services.dynamodb

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

import com.typesafe.scalalogging.LazyLogging

import software.amazon.awssdk.services.dynamodb.model._
import software.amazon.awssdk.services.dynamodb.paginators._

object MockDynamoDbAsyncClient {
  def newProxy(clock: Clock): DynamoDbAsyncClient = {
    val ctype = classOf[DynamoDbAsyncClient]
    val mock = new MockDynamoDbAsyncClient(clock)
    val handler = new InvocationHandler() {

      override def invoke(proxy: Object, method: Method, args: Array[Any]): Any = {
        try {
          val m = mock.getClass().getMethod(method.getName(), method.getParameterTypes: _*)
          if (args == null) m.invoke(mock) else m.invoke(mock, args: _*)
        }
        catch {
          case e: NoSuchMethodException => {
            throw new UnsupportedOperationException(s"Mock ${method.getName()} not implemented")
          }
        }
      }
    }
    val proxy = Proxy.newProxyInstance(ctype.getClassLoader(), Array(ctype), handler)
      .asInstanceOf[DynamoDbAsyncClient]
    mock.withProxy(proxy)
    proxy
  }
}

case class TableMetadata(
  name: String,
  creationDateTime: Instant,
  keySchema: List[KeySchemaElement],
  attributeDefinitions: List[AttributeDefinition],
  provisionedThroughput: ProvisionedThroughputDescription
) {
  assert(keySchema.forall(k => attributeDefinitions.exists(_.attributeName == k.attributeName)))
  assert(keySchema.forall(k => List("HASH","RANGE").contains(k.keyTypeAsString)))

  def key(items: Map[String,AttributeValue], exactMatch: Boolean = true): List[AttributeValue] = {
    if (exactMatch && keySchema.size != items.size) {
      throw new IllegalArgumentException(s"Key mismatch [${keySchema}] [${items}]")
    }
    keySchema.map(k => {
      items.get(k.attributeName) match {
        case Some(item) => item
        case None => {
          throw ResourceNotFoundException.builder()
            .message(s"Index attribute ${k.attributeName} missing")
            .build()
        }
      }
    })
  }
}

class MockDynamoDbAsyncClient(clock: Clock) extends LazyLogging {
  type Key = List[AttributeValue]
  type Names = Map[String,String]
  type Values = Map[String,AttributeValue]
  type Cache = ConcurrentHashMap[Key,Values]
  case class GsiInfo(
    keySchema: List[KeySchemaElement],
    index: ConcurrentHashMap[Key, ConcurrentHashMap[Key, Values]]
  )

  case class TableData(
    metadata: TableMetadata,
    cache: Cache,
    gsis: ConcurrentHashMap[String, GsiInfo]
  )

  import MockUtil._

  val proxyRef = new AtomicReference[DynamoDbAsyncClient]()
  def withProxy(proxy: DynamoDbAsyncClient): Unit = proxyRef.set(proxy)

  lazy val condAnd = """(.+) AND (.+)""".r
  lazy val condOr = """(.+) OR (.+)""".r
  lazy val condSimple = """(\S+) (=|<>|<|<=|>|>=) (\S+)""".r
  lazy val condAttrExists = """attribute_exists\(([^\)]+)\)""".r
  lazy val condAttrNotExists = """attribute_not_exists\(([^\)]+)\)""".r
  lazy val condAttrType = """attribute_type\(([^\),]+), ?([^\),]+)\)""".r
  lazy val condBeginsWith = """begins_with\(([^\),]+), ?([^\),]+)\)""".r
  lazy val condContains = """contains\(([^\),]+), ?([^\),]+)\)""".r
  lazy val condSize = """size\(([^\)]+)\)""".r

  lazy val updateExpr = """((?:set|SET)\s+(.+?))?(\s+(?:remove|REMOVE)\s+(.+?))?""".r
  lazy val setSimple = """(\S+) = (\S+)""".r
  lazy val setComplex = """(\S+) = (\S+) (\+|-) (\S+)""".r

  logger.info("Created mock DynamoDbAsyncClient")

  val tables = new ConcurrentHashMap[String,TableData]()

  private def gsiKey(item: Values, keySchema: List[KeySchemaElement]): Option[Key] = {
    val keys = keySchema.flatMap(k => item.get(k.attributeName))
    if (keys.size == keySchema.size) Some(keys) else None
  }

  private def indexItem(data: TableData, primaryKey: Key, item: Values): Unit = {
    data.gsis.asScala.foreach { case (_, gsi) =>
      gsiKey(item, gsi.keySchema).foreach { gk =>
        gsi.index.computeIfAbsent(gk, _ => new ConcurrentHashMap()).put(primaryKey, item)
      }
    }
  }

  private def unindexItem(data: TableData, primaryKey: Key, item: Values): Unit = {
    data.gsis.asScala.foreach { case (_, gsi) =>
      gsiKey(item, gsi.keySchema).foreach { gk =>
        val bucket = gsi.index.get(gk)
        if (bucket != null) {
          bucket.remove(primaryKey)
          if (bucket.isEmpty) gsi.index.remove(gk)
        }
      }
    }
  }

  private def extractGsiKey(
    condition: String,
    keySchema: List[KeySchemaElement],
    attrNames: Names,
    attrValues: Values
  ): Option[Key] = {
    val hashAttr = keySchema.find(_.keyTypeAsString == "HASH").map(_.attributeName)
    val rangeAttr = keySchema.find(_.keyTypeAsString == "RANGE").map(_.attributeName)
    condition match {
      case condSimple(op1, "=", op2) =>
        val attrName = if (op1.startsWith("#")) attrNames.getOrElse(op1, op1) else op1
        if (hashAttr.contains(attrName) || rangeAttr.contains(attrName))
          attrValues.get(op2).map(v => List(v))
        else None
      case condAnd(c1, c2) =>
        for {
          k1 <- extractGsiKey(c1, keySchema, attrNames, attrValues)
          k2 <- extractGsiKey(c2, keySchema, attrNames, attrValues)
        } yield k1 ++ k2
      case _ => None
    }
  }

  def close(): Unit = {}

  def serviceName(): String = DynamoDbAsyncClient.SERVICE_NAME

  def operandToAttr(
    v: String,
    values: Values,
    attrNames: Names,
    attrValues: Values,
  ): AttributeValue = {
    val opt = v match {
      case condSize(path) => {
        values.get(path).map(attr => {
          attrToAny(attr) match {
            case s: String => nAttr(s.size)
            case b: Array[_] => nAttr(b.size)
            case l: List[_] => nAttr(l.size)
            case m: Map[_,_] => nAttr(m.size)
            case _ => throw new IllegalArgumentException(s"Size not supported [${attr}]")
          }
        })
      }
      case key if key.startsWith(":") => attrValues.get(v)
      case key if key.startsWith("#") => {
        val name = attrNames.get(v).orNull
        if (name == null) throw new IllegalArgumentException(s"Name is not defined [${v}]")
        values.get(name)
      }
      case key => values.get(v)
    }
    if (opt.isEmpty) throw new IllegalArgumentException(s"Attribute is not defined [${v}] [${values}]")
    opt.get
  }

  def operandToAny(
    v: String,
    values: Values,
    attrNames: Names,
    attrValues: Values,
  ): Any = {
    attrToAny(operandToAttr(v, values, attrNames, attrValues))
  }

  private def conditionCheck(
    values: Values,
    condition: String,
    attrNames: Names,
    attrValues: Values
  ): Boolean = {
    def asString(attr: AttributeValue): String = {
      val v = attrToAny(attr)
      if (!v.isInstanceOf[String]) {
        logger.error(s"Attribute must be a string [${attr}]")
        throw new IllegalArgumentException(s"Attribute must be a string [${attr}]")
      }
      v.asInstanceOf[String]
    }
    condition match {
      case null => true
      case "" => true
      case condAnd(cond1, cond2) => {
        conditionCheck(values, cond1, attrNames, attrValues) &&
        conditionCheck(values, cond2, attrNames, attrValues)
      }
      case condOr(cond1, cond2) => {
        conditionCheck(values, cond1, attrNames, attrValues) ||
        conditionCheck(values, cond2, attrNames, attrValues)
      }
      case condSimple(op1, comparator, op2) => {
        val v1 = operandToAny(op1, values, attrNames, attrValues)
        val v2 = operandToAny(op2, values, attrNames, attrValues)
        logger.debug(s"${condition}: v1=${v1}, v2=${v2} [$values}]")
        (v1, v2) match {
          case (a: Long, b: Long) => {
            comparator match {
              case "="  => a == b
              case "<>" => a != b
              case "<"  => a < b
              case "<=" => a <= b
              case ">"  => a > b
              case ">=" => a >= b
            }
          }
          case (a: Double, b: Double) => {
            comparator match {
              case "="  => a == b
              case "<>" => a != b
              case "<"  => a < b
              case "<=" => a <= b
              case ">"  => a > b
              case ">=" => a >= b
            }
          }
          case (a: String, b: String) => {
            comparator match {
              case "="  => a == b
              case "<>" => a != b
              case "<"  => a < b
              case "<=" => a <= b
              case ">"  => a > b
              case ">=" => a >= b
            }
          }
          case (a: Boolean, b: Boolean) => {
            comparator match {
              case "="  => a == b
              case "<>" => a != b
              case "<"  => a < b
              case "<=" => a <= b
              case ">"  => a > b
              case ">=" => a >= b
            }
          }
          case _ => throw new IllegalArgumentException(s"Operands of different types [${v1},${v2}]")
        }
      }
      case condAttrExists(path) => {
        values.contains(path)
      }
      case condAttrNotExists(path) => {
        !values.contains(path)
      }
      case condAttrType(path, op) => {
        val v = operandToAny(op, values, attrNames, attrValues)
        values.get(path).map(attr => attrType(attr) == v).getOrElse(false)
      }
      case condBeginsWith(path, op) => {
        values.get(path) match {
          case Some(attr) => {
            asString(attr).startsWith(asString(operandToAttr(op, values, attrNames, attrValues)))
          }
          case None => false
        }
      }
      case condContains(path, op) => {
        val v = asString(operandToAttr(op, values, attrNames, attrValues))
        values.get(path) match {
          case Some(attr) => {
            attrType(attr) match {
              case "S" => attrToAny(attr).asInstanceOf[String].contains(v)
              case "SS" => attrToAny(attr).asInstanceOf[List[String]].contains(v)
              case _ => throw new IllegalArgumentException(s"Must be set or string [${attr}]")
            }
          }
          case None => false
        }
      }
      case _ => throw new IllegalArgumentException(s"Unknown condition expression ${condition}")
    }
  }

  private def dataKey(item: ConditionCheck): (TableData,List[AttributeValue]) = {
    val data = tables.get(item.tableName)
    if (data == null) throw new IllegalArgumentException(s"Table does not exist: ${item.tableName}")
    val key = data.metadata.key(item.key().asScala.toMap)
    (data, key)
  }

  private def dataKey(item: Delete): (TableData,List[AttributeValue]) = {
    val data = tables.get(item.tableName)
    if (data == null) throw new IllegalArgumentException(s"Table does not exist: ${item.tableName}")
    val key = data.metadata.key(item.key().asScala.toMap)
    (data, key)
  }

  private def dataKey(item: Put): (TableData,List[AttributeValue]) = {
    val data = tables.get(item.tableName)
    if (data == null) throw new IllegalArgumentException(s"Table does not exist: ${item.tableName}")
    val key = data.metadata.key(item.item().asScala.toMap, false)
    (data, key)
  }

  private def dataKey(item: Update): (TableData,List[AttributeValue]) = {
    val data = tables.get(item.tableName)
    if (data == null) throw new IllegalArgumentException(s"Table does not exist: ${item.tableName}")
    val key = data.metadata.key(item.key().asScala.toMap)
    (data, key)
  }

  private def doConditionCheck(item: ConditionCheck): Boolean = {
    val (data, key) = dataKey(item)
    val items = Option(data.cache.get(key)).getOrElse(Map.empty)
    val names = item.expressionAttributeNames.asScala.toMap
    val values = item.expressionAttributeValues.asScala.toMap
    conditionCheck(items, item.conditionExpression, names, values)
  }

  private def doConditionCheck(item: Delete): Boolean = {
    val (data, key) = dataKey(item)
    val items = Option(data.cache.get(key)).getOrElse(Map.empty)
    val names = item.expressionAttributeNames.asScala.toMap
    val values = item.expressionAttributeValues.asScala.toMap
    conditionCheck(items, item.conditionExpression, names, values)
  }

  private def doConditionCheck(item: Put): Boolean = {
    val (data, key) = dataKey(item)
    val items = Option(data.cache.get(key)).getOrElse(Map.empty)
    val names = item.expressionAttributeNames.asScala.toMap
    val values = item.expressionAttributeValues.asScala.toMap
    conditionCheck(items, item.conditionExpression, names, values)
  }

  private def doConditionCheck(item: Update): Boolean = {
    val (data, key) = dataKey(item)
    val items = Option(data.cache.get(key)).getOrElse(Map.empty)
    val names = item.expressionAttributeNames.asScala.toMap
    val values = item.expressionAttributeValues.asScala.toMap
    conditionCheck(items, item.conditionExpression, names, values)
  }

  private def doAction(item: Delete): Unit = {
    val (data, key) = dataKey(item)
    val old = data.cache.get(key)
    if (old != null) {
      unindexItem(data, key, old)
      data.cache.remove(key)
    }
  }

  private def doAction(item: Put): Unit = {
    val (data, key) = dataKey(item)
    Option(data.cache.get(key)).foreach(old => unindexItem(data, key, old))
    val items = item.item().asScala.toMap
    data.cache.put(key, items)
    indexItem(data, key, items)
  }

  private def doAction(item: Update): Unit = {
    val (data, key) = dataKey(item)
    val values = data.cache.get(key)
    if (values == null) throw new IllegalArgumentException(s"Values do not exist: ${key}")
    item.updateExpression match {
      case updateExpr(setCmd, setExpr, removeCmd, removeExpr) => {
        val items = scala.collection.mutable.Map.from(values)
        Option(setExpr).map(_.split(", ")).toList.flatten.foreach({
          case setSimple(n, v) => {
            val attr = item.expressionAttributeValues.get(v)
            if (attr == null) {
              throw new IllegalArgumentException(s"Attribute does not exist: [${v}]")
            }
            val key = n match {
              case v if v.startsWith("#") => {
                val name = item.expressionAttributeNames.asScala.toMap.get(v).orNull
                if (name == null) throw new IllegalArgumentException(s"Name is not defined [${v}]")
                name
              }
              case v => v
            }
            items += key -> attr
          }
          case setComplex(n, op1, func, op2) => {
            val attrNames = item.expressionAttributeNames.asScala.toMap
            val attrValues = item.expressionAttributeValues.asScala.toMap
            val key = n match {
              case v if v.startsWith("#") => {
                val name = attrNames.get(v).orNull
                if (name == null) throw new IllegalArgumentException(s"Name is not defined [${v}]")
                name
              }
              case v => v
            }
            val v1 = operandToAny(op1, values, attrNames, attrValues)
            val v2 = operandToAny(op2, values, attrNames, attrValues)
            val attr = (v1, v2) match {
              case (a: Long, b: Long) => nAttr({ if (func == "+") a + b else a - b })
              case (a: Double, b: Double) => nAttr({ if (func == "+") a + b else a - b })
              case (a, b) => throw new IllegalArgumentException(s"Expected numbers [${a},${b}]")
            }
            items += key -> attr
          }
          case _ => {
            throw new IllegalArgumentException(
              s"Unknown update expression: [${item.updateExpression}]"
            )
          }
        })
        Option(removeExpr).map(_.split(", ")).toList.flatten.foreach(k => {
          if (items.remove(k).isEmpty) {
            throw new IllegalArgumentException(s"Attribute does not exist: [${k}]")
          }
        })
        unindexItem(data, key, values)
        data.cache.put(key, items.toMap)
        indexItem(data, key, items.toMap)
      }
      case _ => {
        throw new IllegalArgumentException(s"Unknown update expression: [${item.updateExpression}]")
      }
    }
  }

  private def tableDescription(data: TableData): TableDescription = {
    val builder = TableDescription.builder()
      .tableName(data.metadata.name)
      .creationDateTime(data.metadata.creationDateTime)
      .keySchema(data.metadata.keySchema.asJava)
      .attributeDefinitions(data.metadata.attributeDefinitions.asJava)
      .provisionedThroughput(data.metadata.provisionedThroughput)
    val gsiDescriptions = data.gsis.asScala.map { case (name, gsi) =>
      GlobalSecondaryIndexDescription.builder()
        .indexName(name)
        .keySchema(gsi.keySchema.asJava)
        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
        .build()
    }.toList
    if (gsiDescriptions.nonEmpty) {
      builder.globalSecondaryIndexes(gsiDescriptions.asJava)
    }
    builder.build()
  }

  def createTable(request: CreateTableRequest): CompletableFuture[CreateTableResponse] = {
    CompletableFuture.supplyAsync(new Supplier[CreateTableResponse]() {
      override def get(): CreateTableResponse = {
        val now = clock.instant()
        val metadata = TableMetadata(
          request.tableName,
          now,
          request.keySchema.asScala.toList,
          request.attributeDefinitions.asScala.toList,
          ProvisionedThroughputDescription.builder()
            .readCapacityUnits(request.provisionedThroughput.readCapacityUnits)
            .writeCapacityUnits(request.provisionedThroughput.writeCapacityUnits)
            .lastDecreaseDateTime(Instant.EPOCH)
            .lastIncreaseDateTime(now)
            .numberOfDecreasesToday(0)
            .build()
        )
        val gsis = new ConcurrentHashMap[String, GsiInfo]()
        Option(request.globalSecondaryIndexes).foreach(_.asScala.foreach { gsi =>
          gsis.put(gsi.indexName, GsiInfo(
            gsi.keySchema.asScala.toList,
            new ConcurrentHashMap()
          ))
        })
        val tableData = TableData(metadata, new Cache(), gsis)
        if (tables.putIfAbsent(request.tableName, tableData) != null) {
          throw ResourceInUseException.builder()
            .message(s"Table ${request.tableName} already exists")
            .build()
        }
        logger.info(s"Created table [${request.tableName}]")
        CreateTableResponse.builder().tableDescription(tableDescription(tableData)).build()
      }
    })
  }

  def describeTable(request: DescribeTableRequest): CompletableFuture[DescribeTableResponse] = {
    CompletableFuture.supplyAsync(new Supplier[DescribeTableResponse]() {
      override def get(): DescribeTableResponse = {
        val data = tables.get(request.tableName)
        if (data == null) {
          throw ResourceNotFoundException.builder()
            .message(s"Table ${request.tableName} does not exist")
            .build()
        }
        DescribeTableResponse.builder().table(tableDescription(data)).build()
      }
    })
  }

  def getItem(request: GetItemRequest): CompletableFuture[GetItemResponse] = {
    CompletableFuture.supplyAsync(new Supplier[GetItemResponse]() {
      override def get(): GetItemResponse = {
        val data = tables.get(request.tableName)
        if (data == null) {
          throw ResourceNotFoundException.builder()
            .message(s"Table ${request.tableName} does not exist")
            .build()
        }
        val key = data.metadata.key(request.key().asScala.toMap)
        Option(data.cache.get(key)) match {
          case Some(items) => {
            logger.info(s"Getting item: ${key} -> ${items}")
            GetItemResponse.builder()
            .item(request.attributesToGet.asScala.toList match {
              case Nil => items.asJava
              case attrs => attrs.flatMap(n => {
                items.get(n).map(v => n -> v)
              }).toMap.asJava
            })
            .build()
          }
          case None => {
            throw ResourceNotFoundException.builder()
              .message(s"Item not found [${key}]")
              .build()
          }
        }
      }
    })
  }

  def putItem(request: PutItemRequest): CompletableFuture[PutItemResponse] = {
    CompletableFuture.supplyAsync(new Supplier[PutItemResponse]() {
      override def get(): PutItemResponse = {
        val data = tables.get(request.tableName)
        if (data == null) {
          throw ResourceNotFoundException.builder()
            .message(s"Table ${request.tableName} does not exist")
            .build()
        }
        val items = request.item().asScala.toMap
        items.values.map(attrToAny).foreach({
          case "" => {
            throw DynamoDbException.builder()
              .message("An AttributeValue may not contain an empty string")
              .build()
          }
          case _ => {}
        })
        val key = data.metadata.key(items, false)
        logger.info(s"Saving item: ${key} -> ${items}")
        Option(data.cache.get(key)).foreach(old => unindexItem(data, key, old))
        data.cache.put(key, items)
        indexItem(data, key, items)
        PutItemResponse.builder().build()
      }
    })
  }

  def updateItem(request: UpdateItemRequest): CompletableFuture[UpdateItemResponse] = {
    CompletableFuture.supplyAsync(new Supplier[UpdateItemResponse]() {
      override def get(): UpdateItemResponse = {
        val data = tables.get(request.tableName)
        if (data == null) {
          throw ResourceNotFoundException.builder()
            .message(s"Table ${request.tableName} does not exist")
            .build()
        }
        val key = data.metadata.key(request.key().asScala.toMap)
        val values = data.cache.get(key)
        if (values == null) {
          throw ResourceNotFoundException.builder()
            .message(s"Item not found [${key}]")
            .build()
        }
        val names = Option(request.expressionAttributeNames).map(_.asScala.toMap).getOrElse(Map.empty)
        val attrValues = Option(request.expressionAttributeValues).map(_.asScala.toMap).getOrElse(Map.empty)
        Option(request.conditionExpression).foreach { cond =>
          if (!conditionCheck(values, cond, names, attrValues)) {
            throw ConditionalCheckFailedException.builder()
              .message("The conditional request failed")
              .build()
          }
        }
        request.updateExpression match {
          case updateExpr(setCmd, setExpr, removeCmd, removeExpr) =>
            val items = scala.collection.mutable.Map.from(values)
            Option(setExpr).map(_.split(", ")).toList.flatten.foreach({
              case setSimple(n, v) =>
                val attr = attrValues.get(v).orNull
                if (attr == null) {
                  throw new IllegalArgumentException(s"Attribute does not exist: [${v}]")
                }
                val resolvedKey = n match {
                  case v if v.startsWith("#") =>
                    names.getOrElse(v, throw new IllegalArgumentException(s"Name is not defined [${v}]"))
                  case v => v
                }
                items += resolvedKey -> attr
              case setComplex(n, op1, func, op2) =>
                val resolvedKey = n match {
                  case v if v.startsWith("#") =>
                    names.getOrElse(v, throw new IllegalArgumentException(s"Name is not defined [${v}]"))
                  case v => v
                }
                val v1 = operandToAny(op1, values, names, attrValues)
                val v2 = operandToAny(op2, values, names, attrValues)
                val attr = (v1, v2) match {
                  case (a: Long, b: Long) => nAttr({ if (func == "+") a + b else a - b })
                  case (a: Double, b: Double) => nAttr({ if (func == "+") a + b else a - b })
                  case (a, b) => throw new IllegalArgumentException(s"Expected numbers [${a},${b}]")
                }
                items += resolvedKey -> attr
              case other =>
                throw new IllegalArgumentException(s"Unknown update expression: [${request.updateExpression}]")
            })
            Option(removeExpr).map(_.split(", ")).toList.flatten.foreach(k => {
              items.remove(k)
            })
            unindexItem(data, key, values)
            data.cache.put(key, items.toMap)
            indexItem(data, key, items.toMap)
          case _ =>
            throw new IllegalArgumentException(s"Unknown update expression: [${request.updateExpression}]")
        }
        logger.info(s"Updated item: ${key}")
        UpdateItemResponse.builder().build()
      }
    })
  }

  def query(request: QueryRequest): CompletableFuture[QueryResponse] = {
    CompletableFuture.supplyAsync(new Supplier[QueryResponse]() {
      override def get(): QueryResponse = {
        val data = tables.get(request.tableName)
        if (data == null) {
          throw ResourceNotFoundException.builder()
            .message(s"Table ${request.tableName} does not exist")
            .build()
        }
        val names = request.expressionAttributeNames().asScala.toMap
        val values = request.expressionAttributeValues().asScala.toMap
        val order = if (request.scanIndexForward) 1 else -1
        val items = Option(request.indexName).flatMap(n => Option(data.gsis.get(n))) match {
          case Some(gsi) =>
            extractGsiKey(request.keyConditionExpression, gsi.keySchema, names, values) match {
              case Some(gk) =>
                Option(gsi.index.get(gk))
                  .map(_.values.asScala.toList)
                  .getOrElse(List.empty)
              case None =>
                throw new IllegalArgumentException(
                  s"Cannot extract key from condition: ${request.keyConditionExpression}"
                )
            }
          case None =>
            data.cache.asScala.toList
              .sortWith((a, b) => order * lCompare(a._1, b._1) < 0)
              .map(_._2)
              .filter(v => conditionCheck(v, request.keyConditionExpression(), names, values))
        }
        QueryResponse.builder().items(items.map(_.asJava).toList.asJava).build()
      }
    })
  }

  def queryPaginator(request: QueryRequest): QueryPublisher = {
    return new QueryPublisher(proxyRef.get, request)
  }

  def scan(request: ScanRequest): CompletableFuture[ScanResponse] = {
    CompletableFuture.supplyAsync(new Supplier[ScanResponse]() {
      override def get(): ScanResponse = {
        val data = tables.get(request.tableName)
        if (data == null) {
          throw ResourceNotFoundException.builder()
            .message(s"Table ${request.tableName} does not exist")
            .build()
        }
        val items = data.cache.values.asScala.toList
        ScanResponse.builder().count(items.size).items(items.map(_.asJava).asJava).build()
      }
    })
  }

  def scanPaginator(request: ScanRequest): ScanPublisher = {
    return new ScanPublisher(proxyRef.get, request)
  }

  def transactWriteItems(
    request: TransactWriteItemsRequest
  ): CompletableFuture[TransactWriteItemsResponse] = {
    CompletableFuture.supplyAsync(new Supplier[TransactWriteItemsResponse]() {
      override def get(): TransactWriteItemsResponse = {
        val items = request.transactItems().asScala
        val ids = items.map(item => {
          List(
            Option(item.conditionCheck).map(dataKey),
            Option(item.delete).map(dataKey),
            Option(item.put).map(dataKey),
            Option(item.update).map(dataKey),
          ).flatten.map(v => v._1.metadata.name -> v._2)
        })
        if (ids.size != ids.distinct.size) {
          throw new IllegalArgumentException(s"Accessing duplicate item [${ids}]")
        }
        val reasons = items.map { item =>
          val passed =
            Option(item.conditionCheck).map(doConditionCheck).getOrElse(true) &&
            Option(item.delete).map(doConditionCheck).getOrElse(true) &&
            Option(item.put).map(doConditionCheck).getOrElse(true) &&
            Option(item.update).map(doConditionCheck).getOrElse(true)
          if (passed)
            CancellationReason.builder.code("None").build
          else
            CancellationReason.builder.code("ConditionalCheckFailed")
              .message("The conditional request failed").build
        }
        if (reasons.forall(_.code == "None")) {
          items.foreach { item =>
            Option(item.delete).foreach(doAction)
            Option(item.put).foreach(doAction)
            Option(item.update).foreach(doAction)
          }
          TransactWriteItemsResponse.builder().build()
        }
        else {
          throw TransactionCanceledException.builder()
            .message("Transaction cancelled, precondition not satisfied.")
            .cancellationReasons(reasons.asJava)
            .build()
        }
      }
    })
  }

  def updateTable(request: UpdateTableRequest): CompletableFuture[UpdateTableResponse] = {
    CompletableFuture.supplyAsync(new Supplier[UpdateTableResponse]() {
      override def get(): UpdateTableResponse = {
        val data = tables.computeIfPresent(request.tableName, (k, v) => {
          var result = v

          Option(request.provisionedThroughput).foreach { newPt =>
            val now = clock.instant()
            val oldPt = result.metadata.provisionedThroughput
            val isIncrease = oldPt.readCapacityUnits + oldPt.writeCapacityUnits <
                             newPt.readCapacityUnits + newPt.writeCapacityUnits
            result = result.copy(metadata = result.metadata.copy(provisionedThroughput =
              ProvisionedThroughputDescription.builder()
                .readCapacityUnits(newPt.readCapacityUnits)
                .writeCapacityUnits(newPt.writeCapacityUnits)
                .lastDecreaseDateTime(if (isIncrease) oldPt.lastDecreaseDateTime else now)
                .lastIncreaseDateTime(if (isIncrease) now else oldPt.lastIncreaseDateTime)
                .numberOfDecreasesToday(oldPt.numberOfDecreasesToday + { if (isIncrease) 0 else 1 })
                .build()
            ))
          }

          Option(request.globalSecondaryIndexUpdates).foreach(_.asScala.foreach { update =>
            Option(update.create).foreach { create =>
              val gsiInfo = GsiInfo(
                create.keySchema.asScala.toList,
                new ConcurrentHashMap()
              )
              result.gsis.put(create.indexName, gsiInfo)
              // Backfill existing items into the new index
              result.cache.asScala.foreach { case (pk, item) =>
                gsiKey(item, gsiInfo.keySchema).foreach { gk =>
                  gsiInfo.index.computeIfAbsent(gk, _ => new ConcurrentHashMap()).put(pk, item)
                }
              }
              logger.info(s"Created GSI [${create.indexName}] on table [${request.tableName}]")
            }
          })

          Option(request.attributeDefinitions).foreach { defs =>
            result = result.copy(metadata = result.metadata.copy(
              attributeDefinitions = defs.asScala.toList
            ))
          }

          result
        })
        if (data == null) {
          throw ResourceNotFoundException.builder()
            .message(s"Table ${request.tableName} does not exist")
            .build()
        }
        UpdateTableResponse.builder().tableDescription(tableDescription(data)).build()
      }
    })
  }
}
