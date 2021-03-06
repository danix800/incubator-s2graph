/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.s2graph.core.mysqls

import java.util.Properties

import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import scalikejdbc._

class ExperimentSpec extends FlatSpec with Matchers with BeforeAndAfterAll {
  val Ttl = 2
  override def beforeAll(): Unit = {
    /*
    maxSize = config.getInt("cache.max.size")
    ttl = config.getInt("cache.ttl.seconds")
     */
    val props = new Properties()
    props.setProperty("cache.ttl.seconds", Ttl.toString)
    Model.apply(ConfigFactory.load(ConfigFactory.parseProperties(props)))

    implicit val session = AutoSession
    sql"""DELETE FROM buckets""".update().apply()
    sql"""DELETE FROM experiments""".update().apply()

    val expId = sql"""INSERT INTO experiments(service_id, service_name, name, description) VALUES(1, 's1', 'exp1', '')""".updateAndReturnGeneratedKey().apply()
    sql"""INSERT INTO
           buckets(experiment_id, modular, http_verb, api_path, request_body, impression_id)
           VALUES($expId, '1~100', 'POST', '/a/b/c', 'None', 'imp1')""".update().apply()

  }

  "Experiment" should "find bucket list" in {
    Experiment.findBy(1, "exp1") should not be empty

    Experiment.findBy(1, "exp1").foreach { exp =>
      val bucket = exp.buckets.head
      bucket.impressionId should equal("imp1")
    }
  }

  it should "update bucket list after cache ttl time" in {
    Experiment.findBy(1, "exp1").foreach { exp =>
      val bucket = exp.buckets.head
      bucket.impressionId should equal("imp1")

      implicit val session = AutoSession

      sql"""UPDATE buckets SET impression_id = 'imp2' WHERE id = ${bucket.id}""".update().apply()
    }

    // sleep ttl time
    Thread.sleep((Ttl + 1) * 1000)

    // update experiment and bucket
    Experiment.findBy(1, "exp1").foreach(exp => exp.buckets)

    // wait for cache updating
    Thread.sleep(1 * 1000)

    // should be updated
    Experiment.findBy(1, "exp1").foreach { exp =>
      exp.buckets.head.impressionId should equal("imp2")
    }
  }
}
