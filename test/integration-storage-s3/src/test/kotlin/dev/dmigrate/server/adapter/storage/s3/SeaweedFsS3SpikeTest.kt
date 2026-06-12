package dev.dmigrate.server.adapter.storage.s3

import dev.dmigrate.core.util.sha256Hex
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import java.net.URI

/**
 * S3.0-Gate-Spike (ImpPlan-0.9.8-object-storage-s3, Gate-Punkte 3+4).
 *
 * Validiert die SeaweedFS-S3-Kompatibilitaet als reales Demo-Ziel
 * (bi-demo-compose.md), bevor die produktiven Adapter (S3.2/S3.3)
 * gebaut werden. Kein Adapter-Code — direkte AWS-SDK-v2-Aufrufe mit dem
 * gewaehlten `url-connection-client`-Transport. Die kritischen Fragen:
 *  - Liefert SeaweedFS User-Metadata (`x-amz-meta-sha256`) bei HeadObject
 *    zurueck? (Voraussetzung fuer den AlreadyExists/Conflict-Idempotenzpfad.)
 *  - Funktionieren Range-GET und Multipart (>= 5-MiB-Part) gegen SeaweedFS?
 *
 * Container-Setup (Image, Identity, Volume-Flags): SeaweedTestSupport.
 */
private const val BUCKET = "spike"

class SeaweedFsS3SpikeTest : FunSpec({

    val container = newSeaweedS3Container()

    lateinit var s3: S3Client

    beforeSpec {
        container.start()
        s3 = S3Client.builder()
            .endpointOverride(URI.create(container.s3Endpoint()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(SEAWEED_TEST_ACCESS_KEY, SEAWEED_TEST_SECRET_KEY),
                ),
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            // S3.0-Gate-Befund: AWS SDK v2 (>= 2.30) rechnet per Default
            // Integritaets-Checksums (Content-MD5/x-amz-checksum via
            // aws-chunked), die SeaweedFS mit "Content-Md5 not valid" (400)
            // ablehnt. WHEN_REQUIRED schaltet das auf den klassischen Pfad
            // zurueck. Muss im produktiven S3-Client (S3.2) ebenso gesetzt sein.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClient(UrlConnectionHttpClient.create())
            .build()
        s3.createBucket { it.bucket(BUCKET) }
    }

    afterSpec {
        runCatching { s3.close() }
        container.stop()
    }

    test("Gate 3a: PutObject mit User-Metadata -> HeadObject liefert sha256 + Size zurueck") {
        val data = "hello-seaweed".toByteArray()
        val sha = sha256Hex(data)
        s3.putObject(
            PutObjectRequest.builder().bucket(BUCKET).key("art/meta")
                .metadata(mapOf("sha256" to sha, "size-bytes" to data.size.toString())).build(),
            RequestBody.fromBytes(data),
        )
        val head = s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key("art/meta").build())
        head.metadata()["sha256"] shouldBe sha
        head.metadata()["size-bytes"] shouldBe data.size.toString()
        head.contentLength() shouldBe data.size.toLong()
    }

    test("Gate 3b: GetObject Range liefert exakten Teilbereich") {
        val data = "0123456789".toByteArray()
        s3.putObject(
            PutObjectRequest.builder().bucket(BUCKET).key("art/range").build(),
            RequestBody.fromBytes(data),
        )
        val part = s3.getObjectAsBytes(
            GetObjectRequest.builder().bucket(BUCKET).key("art/range").range("bytes=2-5").build(),
        ).asByteArray()
        String(part) shouldBe "2345"
    }

    test("Gate 3c: Multipart-Upload (Part1 = 5 MiB) round-trips") {
        val key = "art/multipart"
        val part1 = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val part2 = "tail".toByteArray()
        val uploadId = s3.createMultipartUpload { it.bucket(BUCKET).key(key) }.uploadId()
        val e1 = s3.uploadPart(
            UploadPartRequest.builder().bucket(BUCKET).key(key).uploadId(uploadId).partNumber(1).build(),
            RequestBody.fromBytes(part1),
        ).eTag()
        val e2 = s3.uploadPart(
            UploadPartRequest.builder().bucket(BUCKET).key(key).uploadId(uploadId).partNumber(2).build(),
            RequestBody.fromBytes(part2),
        ).eTag()
        s3.completeMultipartUpload { req ->
            req.bucket(BUCKET).key(key).uploadId(uploadId).multipartUpload(
                CompletedMultipartUpload.builder().parts(
                    CompletedPart.builder().partNumber(1).eTag(e1).build(),
                    CompletedPart.builder().partNumber(2).eTag(e2).build(),
                ).build(),
            )
        }
        val head = s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build())
        head.contentLength() shouldBe (part1.size + part2.size).toLong()
    }

    test("Gate 4: Re-PutObject mit gleichem sha256 -> HeadObject-Metadata stabil (Idempotenz-Basis)") {
        val data = "idempotent".toByteArray()
        val sha = sha256Hex(data)
        val request = PutObjectRequest.builder().bucket(BUCKET).key("art/idem")
            .metadata(mapOf("sha256" to sha)).build()
        s3.putObject(request, RequestBody.fromBytes(data))
        s3.putObject(request, RequestBody.fromBytes(data))
        s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key("art/idem").build())
            .metadata()["sha256"] shouldBe sha
    }
})
