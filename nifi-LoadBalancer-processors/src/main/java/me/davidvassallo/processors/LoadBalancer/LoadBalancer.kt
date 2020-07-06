/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.davidvassallo.processors.LoadBalancer

import org.apache.nifi.annotation.behavior.ReadsAttribute
import org.apache.nifi.annotation.behavior.ReadsAttributes
import org.apache.nifi.annotation.behavior.WritesAttribute
import org.apache.nifi.annotation.behavior.WritesAttributes
import org.apache.nifi.annotation.documentation.CapabilityDescription
import org.apache.nifi.annotation.documentation.SeeAlso
import org.apache.nifi.annotation.documentation.Tags
import org.apache.nifi.annotation.lifecycle.OnEnabled
import org.apache.nifi.annotation.lifecycle.OnScheduled
import org.apache.nifi.annotation.lifecycle.OnStopped
import org.apache.nifi.components.PropertyDescriptor
import org.apache.nifi.processor.*
import org.apache.nifi.processor.exception.ProcessException
import org.apache.nifi.processor.util.StandardValidators
import java.lang.Exception
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.xml.bind.DatatypeConverter
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.concurrent.schedule
import kotlin.concurrent.timerTask
import kotlin.math.roundToInt


@Tags("distribute", "route", "load balance")
@CapabilityDescription("Provide a description")
@SeeAlso
@ReadsAttributes(ReadsAttribute(attribute = "", description = ""))
@WritesAttributes(WritesAttribute(attribute = "", description = ""))
class LoadBalancer : AbstractProcessor() {
    private var descriptors: List<PropertyDescriptor>? = null

    private var counter = AtomicLong(System.currentTimeMillis())

    @Volatile
    private var relationships: Set<Relationship>? = null

    @Volatile
    private var liveliness = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var healthChecks : ArrayList<TimerTask>? = null

    @Volatile
    private var attributeCleaner : TimerTask? = null

    @Volatile
    private var buckets = ConcurrentHashMap<String, AttributeBucket>()

    companion object {
        val LOADBALANCING_STRATEGY = PropertyDescriptor.Builder().name("LOADBALANCING STRATEGY")
                .displayName("Load Balancing Strategy")
                .description("Determines how to load balance between downstream relationships")
                .allowableValues("Round Robin", "Random", "Attribute Hash")
                .required(true)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .build()

        val ATTRIBUTE_HASH_FIELD = PropertyDescriptor.Builder().name("ATTRIBUTE HASH FIELD")
                .displayName("ATTRIBUTE HASH FIELD")
                .description("If using 'attribute hash' strategy, this setting determines which flowfile attribute to use as a hash")
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .build()

        val ATTRIBUTE_HASH_LIFETIME = PropertyDescriptor.Builder().name("ATTRIBUTE HASH LIFETIME")
                .displayName("ATTRIBUTE HASH LIFETIME")
                .description("If using 'attribute hash' strategy, this setting determines for how long to cache an attribute hash after last being seen in minutes")
                .defaultValue("60")
                .required(true)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .build()

        val ORIGINAL = Relationship.Builder()
                .name("ORIGINAL")
                .description("Original Flow Files")
                .build()

        val FAILED = Relationship.Builder()
                .name("FAILED")
                .description("Failed Flow Files")
                .build()
    }

    override fun init(context: ProcessorInitializationContext) {
        val descriptors: MutableList<PropertyDescriptor> = ArrayList()
        descriptors.add(LOADBALANCING_STRATEGY)
        descriptors.add(ATTRIBUTE_HASH_FIELD)
        descriptors.add(ATTRIBUTE_HASH_LIFETIME)
        this.descriptors = Collections.unmodifiableList(descriptors)

        val relationships: MutableSet<Relationship> = HashSet()
        relationships.add(ORIGINAL)
        relationships.add(FAILED)
        this.relationships = Collections.unmodifiableSet(relationships)
    }

    override fun getRelationships(): Set<Relationship> {
        return relationships!!
    }

    public override fun getSupportedPropertyDescriptors(): List<PropertyDescriptor> {
        return descriptors!!
    }

    public override fun getSupportedDynamicPropertyDescriptor(propertyDescriptorName: String?): PropertyDescriptor? {
        return PropertyDescriptor.Builder().dynamic(true)
                .name(propertyDescriptorName)
                .displayName(propertyDescriptorName)
                .description("Load Balanced Endpoint")
                .required(true)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .build()
    }

    private fun doHealthCheck(name: String, command: String) {

        val healthCommand = Runtime.getRuntime().exec(command)
        healthCommand.waitFor()

        val result = healthCommand.exitValue()==0

        println("Performing health check for $name: $command : $result")

        liveliness[name] = result
    }

    override fun onPropertyModified(descriptor: PropertyDescriptor?, oldValue: String?, newValue: String?) {

        if (healthChecks.isNullOrEmpty()){
            healthChecks = ArrayList()
        }

        if (descriptor!!.isDynamic){
            val newRelationships = HashSet(this.relationships)
            newRelationships.add(Relationship.Builder()
                    .name(descriptor.name)
                    .description(descriptor.description)
                    .build())
            this.relationships = Collections.unmodifiableSet(newRelationships)

            val timerTask = Timer("${descriptor.name} health check", true).schedule(1000, 5000){
                doHealthCheck(descriptor.name, newValue!!)
            }

            healthChecks!!.add(timerTask)
        }

    }

    @OnScheduled
    fun onScheduled(context: ProcessContext?) {

    }


    @OnStopped
    fun onStopped() {
        if (!healthChecks.isNullOrEmpty()) {
            healthChecks!!.forEach { timerTask -> timerTask.cancel() }
        }
        healthChecks = null
    }


    @Throws(ProcessException::class)
    override fun onTrigger(context: ProcessContext, session: ProcessSession) {
        var flowFile = session.get() ?: return

        val lb_strategy = context.getProperty(LOADBALANCING_STRATEGY).value

        if (attributeCleaner == null){
            synchronized(this){
                attributeCleaner = Timer("Attribute Cleaner").schedule(1000, 5000){
                    val threshold = context.getProperty(ATTRIBUTE_HASH_LIFETIME).value.toLong() * 60 * 1000
                    val currentTime = System.currentTimeMillis()
                    buckets.forEach{ (key, value) ->
                        val lastSeen = value.lastSeen
                        if ((currentTime - lastSeen) >  threshold) {
                            buckets.remove(key)
                        }
                    }
                }
            }
        }

        val numberOfDestinations = relationships!!
                .filter { relationship ->  relationship.name != "FAILED"}
                .filter { relationship ->  relationship.name != "ORIGINAL"}
                .size

        if (healthChecks.isNullOrEmpty() || healthChecks!!.size != numberOfDestinations){
            synchronized(this){
                if (healthChecks.isNullOrEmpty()){
                    healthChecks = ArrayList()
                    println("Init Healthchecks")

                    relationships!!
                            .filter { relationship ->  relationship.name != "FAILED"}
                            .filter { relationship ->  relationship.name != "ORIGINAL"}
                            .forEach { relationship ->
                                val timerTask = Timer("${relationship.name} health check", true).schedule(1000, 5000){
                                doHealthCheck(relationship.name, context!!.getProperty(relationship.name).value)
                            }

                        healthChecks!!.add(timerTask)
                    }
                }
            }
        }

        val destinationKeys = liveliness.filter { entry -> entry.value }.keys.toList()

        if (destinationKeys.isEmpty()){
            flowFile = session.putAttribute(flowFile, "error", "no alive destinations")
            session.transfer(flowFile, FAILED)
            return
        }

        var destination = destinationKeys[(Math.random() * (destinationKeys.size - 1)).roundToInt()]

        if (lb_strategy == "Attribute Hash"){
            val md = MessageDigest.getInstance("MD5")
            md.update(flowFile.getAttribute((context.getProperty(ATTRIBUTE_HASH_FIELD)).value).toByteArray())
            val digest = md.digest()
            val attributeHash = DatatypeConverter.printHexBinary(digest).toUpperCase()



            if (buckets.containsKey(attributeHash)){
                val bucket = buckets[attributeHash]
                val currentTime = System.currentTimeMillis()
                bucket!!.lastSeen = currentTime

                if (liveliness.containsKey(bucket!!.destination)){
                    if (liveliness[bucket!!.destination]!!){
                        destination = bucket!!.destination
                    } else {
                        bucket!!.destination = destination
                    }
                } else {
                    throw(Exception("Destination cannot be checked for liveliness"))
                }
            } else {
                val bucket = AttributeBucket(attributeHash, destination, System.currentTimeMillis())
                buckets[attributeHash] = bucket
            }



        } else if (lb_strategy == "Round Robin") {
            val currentCounter = counter.incrementAndGet()
            destination = destinationKeys[(currentCounter % destinationKeys.size).toInt()]
        }

        session.transfer(flowFile, getRelationships().find { relationship: Relationship ->  relationship.name == destination})

    }

}