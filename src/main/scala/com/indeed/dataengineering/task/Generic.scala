package com.indeed.dataengineering.task


/**
  * Created by aguyyala on 10/19/17.
  */


import com.datastax.driver.core.Cluster
import com.indeed.dataengineering.AnalyticsTaskApp._
import com.datastax.spark.connector.cql.CassandraConnector
import collection.JavaConverters._
import scala.collection.mutable
import org.apache.spark.sql.DataFrame
import scala.language.reflectiveCalls


class Generic {

  def run(): Unit = {

    val runClass = conf("runClass")

    val Array(brokers, topics) = Array(conf("kafka.brokers"), conf("kafka.topic"))
    log.info(s"Initialized the Kafka brokers and topics to $brokers and $topics")

    log.info(s"Create Cassandra connector by passing host as ${conf("cassandra.host")}")
    val connector = CassandraConnector(spark.sparkContext.getConf.set("spark.cassandra.connection.host", conf("cassandra.host")))

    log.info("Connect to cassandra cluster")
    val cluster = Cluster.builder().addContactPoints(conf("cassandra.host").split(","): _*).build()
    val session = cluster.connect("metadata")

    val query = s"select topic, partition, offset from streaming_metadata where job = '${runClass.split("\\.").last}';"
    log.info(s"Running Query in Cassandra to fetch partitions and offsets: $query")
    val res = session.execute(query).all.asScala.toArray

    val resMap = mutable.Map[String, mutable.Map[Int, Long]]()

    res.foreach { rec =>
      val topic = rec.getString("topic")
      val partition = rec.getInt("partition")
      val offset = rec.getLong("offset")
      val value = resMap.getOrElse(topic, mutable.Map[Int, Long]())
      resMap += topic -> (value + (partition -> Math.min(value.getOrElse(partition, offset), offset)))
    }

    val assignString = "{" + resMap.map { case (k, v) => s""""$k":[${v.keys.mkString(",")}]""" }.mkString(",") + "}"
    val offsetString = "{" + resMap.map { case (k, v) => s""""$k":{${v.map { case (p, o) => '"' + s"$p" + '"' + s":$o" }.mkString(",")}}""" }.mkString(",") + "}"

    log.info(s"Assign following topics and partitions: $assignString")
    log.info(s"Starting from the following offsets: $offsetString")

    log.info("Read Kafka streams")
    val kafkaStream = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", brokers)
      .option("assign", assignString)
      .option("startingOffsets", offsetString)
      .option("failOnDataLoss", conf.getOrElse("failOnDataLoss", "false"))
      .load()
    //.option("subscribe", topics)
    //.option("startingOffsets", s""" {"${conf("kafka.topic")}":{"0":-1}} """)
    //.option("assign", """{"maxwell":[4,7,1,9,3]}""")

    log.info("Extract value and map from Kafka consumer records")
    val rawData = kafkaStream.selectExpr("topic", "partition", "offset", "timestamp AS kafka_timestamp", "CAST(value AS STRING)")

    val clazz = Class.forName(runClass).newInstance.asInstanceOf[{ def run(rawData: DataFrame, connector: CassandraConnector): Unit }]
    clazz.run(rawData, connector)
  }

}


