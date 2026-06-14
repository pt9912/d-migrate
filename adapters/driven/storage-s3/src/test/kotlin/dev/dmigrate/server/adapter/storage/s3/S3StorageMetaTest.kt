package dev.dmigrate.server.adapter.storage.s3

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.URI

class S3StorageConfigTest : FunSpec({

    test("toString redacts access and secret keys") {
        val rendered = S3StorageConfig(
            bucket = "demo",
            accessKey = "AKIAEXAMPLE",
            secretKey = "supersecret",
        ).toString()

        rendered shouldContain "bucket=demo"
        rendered shouldContain "***"
        rendered shouldNotContain "AKIAEXAMPLE"
        rendered shouldNotContain "supersecret"
    }

    test("toString shows null credentials as null, not '***'") {
        S3StorageConfig(bucket = "demo").toString() shouldContain "accessKey=null"
    }
})

class S3ClientFactoryTest : FunSpec({

    test("create builds a client with endpoint override and static credentials") {
        val client = S3ClientFactory.create(
            S3StorageConfig(
                bucket = "b",
                endpoint = URI.create("http://localhost:9000"),
                accessKey = "k",
                secretKey = "s",
            ),
        )
        client.shouldNotBeNull()
        client.close()
    }

    test("create builds a client without endpoint or static credentials (default chain)") {
        val client = S3ClientFactory.create(S3StorageConfig(bucket = "b"))
        client.shouldNotBeNull()
        client.close()
    }
})
