package com.databricks.spark.avro

import java.io.File
import java.nio.ByteBuffer
import java.sql.Timestamp

import scala.collection.mutable.ArrayBuffer

import com.google.common.io.Files
import org.apache.commons.io.FileUtils
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.test._
import org.apache.spark.sql.types._
import org.scalatest.FunSuite
import org.scalatest.Matchers

import scala.util.Random

/* Implicits */
import TestSQLContext._

private[avro] object TestUtils extends Matchers {

  /**
   * This function checks that all records in a file match the original
   * record.
   */
  def checkReloadMatchesSaved(testFile: String, avroDir: String) = {

    def rowToString(row: Row): Seq[String] = {
      row.toSeq.map {
        case null => "NULL" // HashSets can't have null in them, so we use a string instead
        case arrayBuffer: ArrayBuffer[Any @unchecked] => arrayBuffer.toArray.mkString(" ")
        case arrayByte: Array[Byte] => arrayByte.mkString(" ")
        case other => other.toString
      }
    }

    val originalEntries = TestSQLContext.avroFile(testFile).collect()
    val newEntries = TestSQLContext.avroFile(avroDir).collect()

    assert(originalEntries.size == newEntries.size)
    originalEntries.zip(newEntries).zipWithIndex.foreach {
      case ((originalEntry, newEntry), i) =>
        assert(rowToString(originalEntry) == rowToString(newEntry), s"Failed comparing row ${i}.")
    }
  }

  test("Lots of nulls") {
    TestUtils.withTempDir { dir =>
      val schema = StructType(Seq(
        StructField("binary", BinaryType, true),
        StructField("timestamp", TimestampType, true),
        StructField("array", ArrayType(ShortType), true),
        StructField("map", MapType(StringType, StringType), true),
        StructField("struct", StructType(Seq(StructField("int", IntegerType, true))))))
      val rdd = sparkContext.parallelize(Seq[Row](
        Row(null, new Timestamp(1), Array[Short](1,2,3), null, null),
        Row(null, null, null, null, null),
        Row(null, null, null, null, null),
        Row(null, null, null, null, null)))
      val df = TestSQLContext.createDataFrame(rdd, schema)
      df.write.avro(dir.toString)
      assert(TestSQLContext.read.avro(dir.toString).count == rdd.count)
    }
  }

  test("Struct field type") {
    TestUtils.withTempDir { dir =>
      val schema = StructType(Seq(
        StructField("float", FloatType, true),
        StructField("short", ShortType, true),
        StructField("byte", ByteType, true),
        StructField("boolean", BooleanType, true)
      ))
      val rdd = sparkContext.parallelize(Seq(
        Row(1f, 1.toShort, 1.toByte, true),
        Row(2f, 2.toShort, 2.toByte, true),
        Row(3f, 3.toShort, 3.toByte, true)
      ))
      val df = TestSQLContext.createDataFrame(rdd, schema)
      df.write.avro(dir.toString)
      assert(TestSQLContext.read.avro(dir.toString).count == rdd.count)
    }
  }

  /**
   * This function generates a random ByteBuffer of a given size.
   */
  private[avro] def generateRandomByteBuffer(rand: Random, size: Int): ByteBuffer = {
    val bb = ByteBuffer.allocate(size)
    val arrayOfBytes = new Array[Byte](size)
    rand.nextBytes(arrayOfBytes)
    bb.put(arrayOfBytes)
  }
}

  test("Array data types") {
    TestUtils.withTempDir { dir =>
      val testSchema = StructType(Seq(
        StructField("byte_array", ArrayType(ByteType), true),
        StructField("short_array", ArrayType(ShortType), true),
        StructField("float_array", ArrayType(FloatType), true),
        StructField("bool_array", ArrayType(BooleanType), true),
        StructField("long_array", ArrayType(LongType), true),
        StructField("double_array", ArrayType(DoubleType), true),
        StructField("decimal_array", ArrayType(DecimalType(5, 5)), true),
        StructField("bin_array", ArrayType(BinaryType), true),
        StructField("timestamp_array", ArrayType(TimestampType), true),
        StructField("array_array", ArrayType(ArrayType(StringType), true), true),
        StructField("struct_array", ArrayType(StructType(Seq(StructField("name", StringType, true)))))))

      val arrayOfByte = new Array[Byte](4)
      for (i <- arrayOfByte.indices) {
        arrayOfByte(i) = i.toByte
      }

      val rdd = sparkContext.parallelize(Seq(
        Row(arrayOfByte, Array[Short](1,2,3,4), Array[Float](1f, 2f, 3f, 4f),
          Array[Boolean](true, false, true, false), Array[Long](1L, 2L), Array[Double](1.0, 2.0),
          Array[BigDecimal](BigDecimal.valueOf(3)), Array[Array[Byte]](arrayOfByte, arrayOfByte),
          Array[Timestamp](new Timestamp(0)),
          Array[Array[String]](Array[String]("CSH, tearing down the walls that divide us", "-jd")),
          Array[Row](Row("Bobby G. can't swim")))))
      val df = TestSQLContext.createDataFrame(rdd, testSchema)
      df.write.avro(dir.toString)
      assert(TestSQLContext.read.avro(dir.toString).count == rdd.count)
    }
  }

  test("write with compression") {
    TestUtils.withTempDir { dir =>
      val AVRO_COMPRESSION_CODEC = "spark.sql.avro.compression.codec"
      val AVRO_DEFLATE_LEVEL = "spark.sql.avro.deflate.level"
      val uncompressDir = s"$dir/uncompress"
      val deflateDir = s"$dir/deflate"
      val snappyDir = s"$dir/snappy"
      val fakeDir = s"$dir/fake"

      val df = TestSQLContext.read.avro(testFile)
      TestSQLContext.setConf(AVRO_COMPRESSION_CODEC, "uncompressed")
      df.write.avro(uncompressDir)
      TestSQLContext.setConf(AVRO_COMPRESSION_CODEC, "deflate")
      TestSQLContext.setConf(AVRO_DEFLATE_LEVEL, "9")
      df.write.avro(deflateDir)
      TestSQLContext.setConf(AVRO_COMPRESSION_CODEC, "snappy")
      df.write.avro(snappyDir)

      val uncompressSize = FileUtils.sizeOfDirectory(new File(uncompressDir))
      val deflateSize = FileUtils.sizeOfDirectory(new File(deflateDir))
      val snappySize = FileUtils.sizeOfDirectory(new File(snappyDir))

      assert(uncompressSize > deflateSize)
      assert(snappySize > deflateSize)
    }
  }

  test("dsl test") {
    val results = TestSQLContext.read.avro(episodesFile).select("title").collect()
    assert(results.length === 8)
  }

  test("support of various data types") {
    // This test uses data from test.avro. You can see the data and the schema of this file in
    // test.json and test.avsc
    val all = TestSQLContext.read.avro(testFile).collect()
    assert(all.length == 3)

    val str = TestSQLContext.read.avro(testFile).select("string").collect()
    assert(str.map(_(0)).toSet.contains("Terran is IMBA!"))

    val simple_map = TestSQLContext.read.avro(testFile).select("simple_map").collect()
    assert(simple_map(0)(0).getClass.toString.contains("Map"))
    assert(simple_map.map(_(0).asInstanceOf[Map[String, Some[Int]]].size).toSet == Set(2, 0))

    val union0 = TestSQLContext.read.avro(testFile).select("union_string_null").collect()
    assert(union0.map(_(0)).toSet == Set("abc", "123", null))

    val union1 = TestSQLContext.read.avro(testFile).select("union_int_long_null").collect()
    assert(union1.map(_(0)).toSet == Set(66, 1, null))

    val union2 = TestSQLContext.read.avro(testFile).select("union_float_double").collect()
    assert(union2.map(x => new java.lang.Double(x(0).toString)).exists(p => Math.abs(p - Math.PI) < 0.001))

    val fixed = TestSQLContext.read.avro(testFile).select("fixed3").collect()
    assert(fixed.map(_(0).asInstanceOf[Array[Byte]]).exists(p => p(1) == 3))

    val enum = TestSQLContext.read.avro(testFile).select("enum").collect()
    assert(enum.map(_(0)).toSet == Set("SPADES", "CLUBS", "DIAMONDS"))

    val record = TestSQLContext.read.avro(testFile).select("record").collect()
    assert(record(0)(0).getClass.toString.contains("Row"))
    assert(record.map(_(0).asInstanceOf[Row](0)).contains("TEST_STR123"))

    val array_of_boolean = TestSQLContext.read.avro(testFile).select("array_of_boolean").collect()
    assert(array_of_boolean.map(_(0).asInstanceOf[Seq[Boolean]].size).toSet == Set(3, 1, 0))

    val bytes = TestSQLContext.read.avro(testFile).select("bytes").collect()
    assert(bytes.map(_(0).asInstanceOf[Array[Byte]].length).toSet == Set(3, 1, 0))
  }

  test("sql test") {
    sql(
      s"""
         |CREATE TEMPORARY TABLE avroTable
         |USING com.databricks.spark.avro
         |OPTIONS (path "$episodesFile")
      """.stripMargin.replaceAll("\n", " "))

    assert(sql("SELECT * FROM avroTable").collect().length === 8)
  }

  test("conversion to avro and back") {
    // Note that test.avro includes a variety of types, some of which are nullable. We expect to
    // get the same values back.

    val tempDir = Files.createTempDir()
    val avroDir = tempDir + "/avro"
    AvroSaver.save(TestSQLContext.avroFile(testFile), avroDir)

    TestUtils.checkReloadMatchesSaved(testFile, avroDir)
    FileUtils.deleteDirectory(tempDir)
  }

  test("conversion to avro and back with namespace") {
    // Note that test.avro includes a variety of types, some of which are nullable. We expect to
    // get the same values back.
    val name = "AvroTest"
    val namespace = "com.databricks.spark.avro"
    val parameters = Map("recordName" -> name, "recordNamespace" -> namespace)

    val tempDir = Files.createTempDir()
    val avroDir = tempDir + "/namedAvro"
    AvroSaver.save(TestSQLContext.avroFile(testFile), avroDir, parameters)

    TestUtils.checkReloadMatchesSaved(testFile, avroDir)

    // Look at raw file and make sure has namespace info
    val rawSaved = TestSQLContext.sparkContext.textFile(avroDir)
    val schema = rawSaved.first()
    assert(schema.contains(name))
    assert(schema.contains(namespace))

    FileUtils.deleteDirectory(tempDir)
  }

  test("converting some specific sparkSQL types to avro") {
    TestUtils.withTempDir { tempDir =>
      val testSchema = StructType(Seq(
        StructField("Name", StringType, false),
        StructField("Length", IntegerType, true),
        StructField("Time", TimestampType, false),
        StructField("Decimal", DecimalType(10, 10), true),
        StructField("Binary", BinaryType, false)))

      val arrayOfByte = new Array[Byte](4)
      for (i <- arrayOfByte.indices) {
        arrayOfByte(i) = i.toByte
      }
      val cityRDD = sparkContext.parallelize(Seq(
        Row("San Francisco", 12, new Timestamp(666), null, arrayOfByte),
        Row("Palo Alto", null, new Timestamp(777), null, arrayOfByte),
        Row("Munich", 8, new Timestamp(42), 3.14, arrayOfByte)))
      val cityDataFrame = TestSQLContext.createDataFrame(cityRDD, testSchema)

      val avroDir = tempDir + "/avro"
      cityDataFrame.write.avro(avroDir)
      assert(TestSQLContext.read.avro(avroDir).collect().length == 3)

      // TimesStamps are converted to longs
      val times = TestSQLContext.read.avro(avroDir).select("Time").collect()
      assert(times.map(_(0)).toSet == Set(666, 777, 42))

      // DecimalType should be converted to string
      val decimals = TestSQLContext.read.avro(avroDir).select("Decimal").collect()
      assert(decimals.map(_(0)).contains("3.14"))

      // There should be a null entry
      val length = TestSQLContext.read.avro(avroDir).select("Length").collect()
      assert(length.map(_(0)).contains(null))

      val binary = TestSQLContext.read.avro(avroDir).select("Binary").collect()
      for (i <- arrayOfByte.indices) {
        assert(binary(1)(0).asInstanceOf[Array[Byte]](i) == arrayOfByte(i))
      }
    }

    FileUtils.deleteDirectory(tempDir)
  }

  test("support of globbed paths") {
    val e1 = TestSQLContext.read.avro("*/test/resources/episodes.avro").collect()
    assert(e1.length == 8)

    val e2 = TestSQLContext.read.avro("src/*/*/episodes.avro").collect()
    assert(e2.length == 8)
  }

  test("reading from invalid path throws exception") {

    // Directory given has no avro files
    intercept[NoFilesException] {
      TestUtils.withTempDir(dir => TestSQLContext.read.avro(dir.getCanonicalPath))
    }

    intercept[NoFilesException] {
      TestSQLContext.read.avro("very/invalid/path/123.avro")
    }

    // In case of globbed path that can't be matched to anything, another exception is thrown (and
    // exception message is helpful)
    intercept[NoFilesException] {
      TestSQLContext.read.avro("*/*/*/*/*/*/*/something.avro")
    }

    intercept[NoAvroFilesException] {
      TestUtils.withTempDir { dir =>
        FileUtils.touch(new File(dir, "test"))
        TestSQLContext.read.avro(dir.toString)
      }
    }

  }

  test("SQL test insert overwrite") {
    val tempEmptyDir = "target/test/empty/"
    // Create a temp directory for table that will be overwritten
    FileUtils.deleteDirectory(new File(tempEmptyDir))
    new File(tempEmptyDir).mkdirs()
    sql(
      s"""
        |CREATE TEMPORARY TABLE episodes
        |USING com.databricks.spark.avro
        |OPTIONS (path "$episodesFile")
      """.stripMargin.replaceAll("\n", " "))
    sql(s"""
        |CREATE TEMPORARY TABLE episodesEmpty
        |(name string, air_date string, doctor int)
        |USING com.databricks.spark.avro
        |OPTIONS (path "$tempEmptyDir")
      """.stripMargin.replaceAll("\n", " "))

    assert(sql("SELECT * FROM episodes").collect().size === 8)
    assert(sql("SELECT * FROM episodesEmpty").collect().isEmpty)

    sql(
      s"""
        |INSERT OVERWRITE TABLE episodesEmpty
        |SELECT * FROM episodes
      """.stripMargin.replaceAll("\n", " "))
    assert(sql("SELECT * FROM episodesEmpty").collect().size == 8)
  }

  test("test save and load") {
    // Test if load works as expected
    val df = TestSQLContext.load(episodesFile, "com.databricks.spark.avro")
    assert(df.count == 8)

    // Test if save works as expected
    val tempSaveDir = "target/test/save/"
    FileUtils.deleteDirectory(new File(tempSaveDir))
    df.save(tempSaveDir, "com.databricks.spark.avro")
    val newDf = TestSQLContext.load(tempSaveDir, "com.databricks.spark.avro")
    assert(newDf.count == 8)
  }

      df.write.avro(tempSaveDir)
      val newDf = TestSQLContext.read.avro(tempSaveDir)
      assert(newDf.count == 8)
    }
  }
}
