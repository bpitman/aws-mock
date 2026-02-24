package software.amazon.awssdk.services.dynamodb

import munit.FunSuite

class MockUtilSuite extends FunSuite {
  import MockUtil._

  test("s") {
    val attr = sAttr("test")
    assertEquals(attrType(attr), "S")
    assertEquals(attrToAny(attr), "test")
  }

  test("n") {
    val attr1 = nAttr(8)
    assertEquals(attrType(attr1), "N")
    assertEquals(attrToAny(attr1), 8L)
    val attr2 = nAttr(8.98)
    assertEquals(attrType(attr2), "N")
    assertEquals(attrToAny(attr2), 8.98)
  }

  test("bool") {
    val attr = boolAttr(false)
    assertEquals(attrType(attr), "BOOL")
    assertEquals(attrToAny(attr), false)
  }

  test("b") {
    val attr = bAttr("hello".getBytes)
    assertEquals(attrType(attr), "B")
    assertEquals(attrToAny(attr).asInstanceOf[Array[Byte]].toList, "hello".getBytes.toList)
  }

  test("ss") {
    val attr = ssAttr(List("test1", "test2"))
    assertEquals(attrType(attr), "SS")
    assertEquals(attrToAny(attr), Set("test1", "test2"))
  }

  test("ns") {
    val attr = nsAttr(List(78.9, 109.0))
    assertEquals(attrType(attr), "NS")
    assertEquals(attrToAny(attr), Set(78.9, 109.0))
  }

  test("bs") {
    val attr = bsAttr(List("test1".getBytes, "test2".getBytes))
    assertEquals(attrType(attr), "BS")
    assertEquals(
      attrToAny(attr).asInstanceOf[Set[Array[Byte]]].toList.map(_.toList).toSet,
      Set("test1".getBytes.toList, "test2".getBytes.toList)
    )
  }

  test("l") {
    val attr = lAttr(List(sAttr("test")))
    assertEquals(attrType(attr), "L")
    assertEquals(attrToAny(attr), List(sAttr("test")))
  }

  test("m") {
    val attr = mAttr(Map("key" -> sAttr("test")))
    assertEquals(attrType(attr), "M")
    assertEquals(attrToAny(attr), Map("key" -> sAttr("test")))
  }

  test("empty") {
    assertEquals(attrType(ssAttr(List())), "SS")
    assertEquals(attrType(lAttr(List())), "L")
    assertEquals(attrType(mAttr(Map())), "M")
  }
}
