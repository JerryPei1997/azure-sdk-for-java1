package com.microsoft.azure.storage

import com.microsoft.azure.storage.blob.*
import com.microsoft.azure.storage.blob.models.BlobDownloadHeaders
import com.microsoft.azure.storage.blob.models.BlobGetPropertiesResponse
import com.microsoft.azure.storage.blob.models.BlobType
import com.microsoft.azure.storage.blob.models.BlockBlobCommitBlockListResponse
import com.microsoft.azure.storage.blob.models.BlockBlobUploadResponse
import com.microsoft.azure.storage.blob.models.StorageErrorCode
import com.microsoft.rest.v2.util.FlowableUtil
import io.reactivex.Flowable
import io.reactivex.functions.Consumer
import spock.lang.Unroll

import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class TransferManagerTest extends APISpec {
    BlockBlobURL bu

    def setup() {
        bu = cu.createBlockBlobURL(generateBlobName())
    }

    @Unroll
    def "Upload file"() {
        setup:
        def channel = AsynchronousFileChannel.open(file.toPath())
        when:
        // Block length will be ignored for single shot.
        CommonRestResponse response = TransferManager.uploadFileToBlockBlob(channel,
                bu, (int) (BlockBlobURL.MAX_STAGE_BLOCK_BYTES / 10),
                new TransferManager.UploadToBlockBlobOptions(null, null, null,
                        null, 20)).blockingGet()

        then:
        responseType.isInstance(response.response()) // Ensure we did the correct type of operation.
        validateBasicHeaders(response)
        compareDataToFile(bu.download(null, null, false).blockingGet().body(),
                file)

        cleanup:
        channel.close()

        where:
        file                                                  || responseType
        getRandomFile(10)                                     || BlockBlobUploadResponse // Single shot
        getRandomFile(BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 1) || BlockBlobCommitBlockListResponse // Multi part
    }

    def compareDataToFile(Flowable<ByteBuffer> data, File file) {
        FileInputStream fis = new FileInputStream(file)

        for (ByteBuffer received : data.blockingIterable()) {
            byte[] readBuffer = new byte[received.remaining()]
            fis.read(readBuffer)
            for (int i = 0; i < received.remaining(); i++) {
                if (readBuffer[i] != received.get(i)) {
                    return false
                }
            }
        }

        fis.close()
        return true
    }

    def "Upload file illegal arguments null"() {
        when:
        TransferManager.uploadFileToBlockBlob(file, url, 5, null).blockingGet()

        then:
        thrown(IllegalArgumentException)

        where:
        file                                                     | url
        null                                                     | new BlockBlobURL(new URL("http://account.com"), StorageURL.createPipeline(primaryCreds, new PipelineOptions()))
        AsynchronousFileChannel.open(getRandomFile(10).toPath()) | null
    }

    @Unroll
    def "Upload file illegal arguments blocks"() {
        setup:
        def channel = AsynchronousFileChannel.open(getRandomFile(fileSize).toPath())

        when:
        TransferManager.uploadFileToBlockBlob(channel, bu,
                blockLength, null).blockingGet()

        then:
        thrown(IllegalArgumentException)

        cleanup:
        channel.close()

        where:
        blockLength                            | fileSize
        -1                                     | 10 // -1 is invalid.
        BlockBlobURL.MAX_STAGE_BLOCK_BYTES + 1 | BlockBlobURL.MAX_STAGE_BLOCK_BYTES + 10 // Block size too big.
        10                                     | BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 // Too many blocks.
    }

    @Unroll
    def "Upload file headers"() {
        setup:
        // We have to use the defaultData here so we can calculate the MD5 on the uploadBlob case.
        File file = File.createTempFile("testUpload", ".txt")
        file.deleteOnExit()
        if (fileSize == "small") {
            FileOutputStream fos = new FileOutputStream(file)
            fos.write(defaultData.array())
            fos.close()
        } else {
            file = getRandomFile(BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10)
        }

        def channel = AsynchronousFileChannel.open(file.toPath())

        when:
        TransferManager.uploadFileToBlockBlob(channel, bu, BlockBlobURL.MAX_STAGE_BLOCK_BYTES,
                new TransferManager.UploadToBlockBlobOptions(null, new BlobHTTPHeaders(cacheControl,
                        contentDisposition, contentEncoding, contentLanguage, contentMD5, contentType), null,
                        null, null)).blockingGet()

        BlobGetPropertiesResponse response = bu.getProperties(null).blockingGet()

        then:
        validateBlobHeaders(response.headers(), cacheControl, contentDisposition, contentEncoding, contentLanguage,
                fileSize == "small" ? MessageDigest.getInstance("MD5").digest(defaultData.array()) : contentMD5,
                contentType == null ? "application/octet-stream" : contentType)
        // For uploading a block blob single-shot, the service will auto calculate an MD5 hash if not present.
        // HTTP default content type is application/octet-stream.

        cleanup:
        channel.close()

        where:
        // The MD5 is simply set on the blob for commitBlockList, not validated.
        fileSize | cacheControl | contentDisposition | contentEncoding | contentLanguage | contentMD5                                                   | contentType
        "small"  | null         | null               | null            | null            | null                                                         | null
        "small"  | "control"    | "disposition"      | "encoding"      | "language"      | MessageDigest.getInstance("MD5").digest(defaultData.array()) | "type"
        "large"  | null         | null               | null            | null            | null                                                         | null
        "large"  | "control"    | "disposition"      | "encoding"      | "language"      | MessageDigest.getInstance("MD5").digest(defaultData.array()) | "type"
    }

    @Unroll
    def "Upload file metadata"() {
        setup:
        Metadata metadata = new Metadata()
        if (key1 != null) {
            metadata.put(key1, value1)
        }
        if (key2 != null) {
            metadata.put(key2, value2)
        }
        def channel = AsynchronousFileChannel.open(getRandomFile(dataSize).toPath())

        when:
        TransferManager.uploadFileToBlockBlob(channel, bu, BlockBlobURL.MAX_STAGE_BLOCK_BYTES,
                new TransferManager.UploadToBlockBlobOptions(null, null, metadata,
                        null, null)).blockingGet()
        BlobGetPropertiesResponse response = bu.getProperties(null).blockingGet()

        then:
        response.statusCode() == 200
        response.headers().metadata() == metadata

        cleanup:
        channel.close()

        where:
        dataSize                                | key1  | value1 | key2   | value2
        10                                      | null  | null   | null   | null
        10                                      | "foo" | "bar"  | "fizz" | "buzz"
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | null  | null   | null   | null
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | "foo" | "bar"  | "fizz" | "buzz"
    }

    @Unroll
    def "Upload file AC"() {
        setup:
        bu.upload(defaultFlowable, defaultDataSize, null, null, null).blockingGet()
        match = setupBlobMatchCondition(bu, match)
        leaseID = setupBlobLeaseCondition(bu, leaseID)
        BlobAccessConditions bac = new BlobAccessConditions(
                new HTTPAccessConditions(modified, unmodified, match, noneMatch), new LeaseAccessConditions(leaseID),
                null, null)
        def channel = AsynchronousFileChannel.open(getRandomFile(dataSize).toPath())

        expect:
        TransferManager.uploadFileToBlockBlob(channel, bu, BlockBlobURL.MAX_STAGE_BLOCK_BYTES,
                new TransferManager.UploadToBlockBlobOptions(null, null, null, bac,
                        null))
                .blockingGet().statusCode() == 201

        cleanup:
        channel.close()

        where:
        dataSize                             | modified | unmodified | match        | noneMatch   | leaseID
        10                                   | null     | null       | null         | null        | null
        10                                      | oldDate | null    | null         | null        | null
        10                                      | null    | newDate | null         | null        | null
        10                                      | null    | null    | receivedEtag | null        | null
        10                                      | null    | null    | null         | garbageEtag | null
        10                                      | null    | null    | null         | null        | receivedLeaseID
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | null    | null    | null         | null        | null
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | oldDate | null    | null         | null        | null
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | null    | newDate | null         | null        | null
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | null    | null    | receivedEtag | null        | null
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | null    | null    | null         | garbageEtag | null
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | null    | null    | null         | null        | receivedLeaseID
    }

    @Unroll
    def "Upload file AC fail"() {
        setup:
        bu.upload(defaultFlowable, defaultDataSize, null, null, null).blockingGet()
        noneMatch = setupBlobMatchCondition(bu, noneMatch)
        setupBlobLeaseCondition(bu, leaseID)
        BlobAccessConditions bac = new BlobAccessConditions(
                new HTTPAccessConditions(modified, unmodified, match, noneMatch), new LeaseAccessConditions(leaseID),
                null, null)
        def channel = AsynchronousFileChannel.open(getRandomFile(dataSize).toPath())

        when:
        TransferManager.uploadFileToBlockBlob(channel, bu, BlockBlobURL.MAX_STAGE_BLOCK_BYTES,
                new TransferManager.UploadToBlockBlobOptions(null, null, null,
                        bac, null))
                .blockingGet()

        then:
        def e = thrown(StorageException)
        e.errorCode() == StorageErrorCode.CONDITION_NOT_MET ||
                e.errorCode() == StorageErrorCode.LEASE_ID_MISMATCH_WITH_BLOB_OPERATION

        cleanup:
        channel.close()

        where:
        dataSize                             | modified | unmodified | match       | noneMatch    | leaseID
        10                                      | newDate | null    | null        | null         | null
        10                                      | null    | oldDate | null        | null         | null
        10                                      | null    | null    | garbageEtag | null         | null
        10                                      | null    | null    | null        | receivedEtag | null
        10                                      | null    | null    | null        | null         | garbageLeaseID
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | newDate | null    | null        | null         | null
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | null    | oldDate | null        | null         | null
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | null    | null    | garbageEtag | null         | null
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | null    | null    | null        | receivedEtag | null
        BlockBlobURL.MAX_UPLOAD_BLOB_BYTES + 10 | null    | null    | null        | null         | garbageLeaseID
    }

    def "Upload options fail"() {
        when:
        new TransferManager.UploadToBlockBlobOptions(null, null, null,
                null, -1)

        then:
        thrown(IllegalArgumentException)
    }

    @Unroll
    def "Download file"() {
        setup:
        def channel = AsynchronousFileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
        TransferManager.uploadFileToBlockBlob(channel, bu, BlockBlobURL.MAX_STAGE_BLOCK_BYTES, null)
                .blockingGet()
        def outChannel = AsynchronousFileChannel.open(getRandomFile(0).toPath(), StandardOpenOption.WRITE,
                StandardOpenOption.READ)

        when:
        def headers = TransferManager.downloadBlobToFile(outChannel, bu, null, null).blockingGet()

        then:
        compareFiles(channel, 0, channel.size(), outChannel)
        headers.blobType() == BlobType.BLOCK_BLOB

        cleanup:
        channel.close() == null
        outChannel.close() == null

        where:
        file                                   | _
        getRandomFile(20)                      | _ // small file
        getRandomFile(16 * 1024 * 1024)        | _ // medium file in several chunks
        getRandomFile(8L * 1026 * 1024 + 10)   | _ // medium file not aligned to block
        getRandomFile(5L * 1024 * 1024 * 1024) | _ // file size exceeds max int
        getRandomFile(0)                       | _ // empty file
    }

    def compareFiles(AsynchronousFileChannel channel1, long offset, long count, AsynchronousFileChannel channel2) {
        int chunkSize = 8 * 1024 * 1024
        long pos = 0

        while (pos < count) {
            chunkSize = Math.min(chunkSize, count - pos)
            def buf1 = FlowableUtil.collectBytesInBuffer(FlowableUtil.readFile(channel1, offset + pos, chunkSize))
                    .blockingGet()
            def buf2 = FlowableUtil.collectBytesInBuffer(FlowableUtil.readFile(channel2, pos, chunkSize)).blockingGet()

            buf1.position(0)
            buf2.position(0)

            if (buf1.compareTo(buf2) != 0) {
                return false
            }

            pos += chunkSize
        }
        if (pos != count && pos != channel2.size()) {
            return false
        }
        return true
    }

    @Unroll
    def "Download file range"() {
        setup:
        def channel = AsynchronousFileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
        TransferManager.uploadFileToBlockBlob(channel, bu, BlockBlobURL.MAX_STAGE_BLOCK_BYTES, null)
                .blockingGet()
        File outFile = getRandomFile(0)
        def outChannel = AsynchronousFileChannel.open(outFile.toPath(), StandardOpenOption.WRITE,
                StandardOpenOption.READ)

        when:
        TransferManager.downloadBlobToFile(outChannel, bu, range, null).blockingGet()

        then:
        compareFiles(channel, range.getOffset(), range.getCount(), outChannel)

        cleanup:
        channel.close()
        outChannel.close()

        where:
        file                           | range                                      | dataSize
        getRandomFile(defaultDataSize) | new BlobRange(0, defaultDataSize)          | defaultDataSize
        getRandomFile(defaultDataSize) | new BlobRange(1, defaultDataSize - 1)      | defaultDataSize - 1
        getRandomFile(defaultDataSize) | new BlobRange(0, defaultDataSize - 1)      | defaultDataSize - 1
        getRandomFile(defaultDataSize) | new BlobRange(0, 10L * 1024 * 1024 * 1024) | defaultDataSize
    }

    def "Download file count null"() {
        setup:
        bu.upload(defaultFlowable, defaultDataSize, null, null, null).blockingGet()
        File outFile = getRandomFile(0)
        def outChannel = AsynchronousFileChannel.open(outFile.toPath(), StandardOpenOption.WRITE,
                StandardOpenOption.READ)

        when:
        TransferManager.downloadBlobToFile(outChannel, bu, new BlobRange(0, null), null)
                .blockingGet()

        then:
        compareDataToFile(defaultFlowable, outFile)

        cleanup:
        outChannel.close()
    }

    @Unroll
    def "Download file AC"() {
        setup:
        def channel = AsynchronousFileChannel.open(getRandomFile(defaultDataSize).toPath(), StandardOpenOption.READ,
                StandardOpenOption.WRITE)
        TransferManager.uploadFileToBlockBlob(channel, bu, BlockBlobURL.MAX_STAGE_BLOCK_BYTES, null)
                .blockingGet()
        def outChannel = AsynchronousFileChannel.open(getRandomFile(0).toPath(), StandardOpenOption.WRITE,
                StandardOpenOption.READ)

        match = setupBlobMatchCondition(bu, match)
        leaseID = setupBlobLeaseCondition(bu, leaseID)
        BlobAccessConditions bac = new BlobAccessConditions(
                new HTTPAccessConditions(modified, unmodified, match, noneMatch), new LeaseAccessConditions(leaseID),
                null, null)

        when:
        TransferManager.downloadBlobToFile(outChannel, bu, null, new TransferManager.DownloadFromBlobOptions(
                null, null, bac, null, null)).blockingGet()

        then:
        compareFiles(channel, 0, channel.size(), outChannel)

        cleanup:
        channel.close()
        outChannel.close()

        where:
        modified | unmodified | match        | noneMatch   | leaseID
        null     | null       | null         | null        | null
        oldDate  | null       | null         | null        | null
        null     | newDate    | null         | null        | null
        null     | null       | receivedEtag | null        | null
        null     | null       | null         | garbageEtag | null
        null     | null       | null         | null        | receivedLeaseID
    }

    @Unroll
    def "Download file AC fail"() {
        setup:
        def channel = AsynchronousFileChannel.open(getRandomFile(defaultDataSize).toPath(), StandardOpenOption.READ,
                StandardOpenOption.WRITE)
        TransferManager.uploadFileToBlockBlob(channel, bu, BlockBlobURL.MAX_STAGE_BLOCK_BYTES, null)
                .blockingGet()
        def outChannel = AsynchronousFileChannel.open(getRandomFile(0).toPath(), StandardOpenOption.WRITE,
                StandardOpenOption.READ)

        noneMatch = setupBlobMatchCondition(bu, noneMatch)
        setupBlobLeaseCondition(bu, leaseID)
        BlobAccessConditions bac = new BlobAccessConditions(
                new HTTPAccessConditions(modified, unmodified, match, noneMatch), new LeaseAccessConditions(leaseID),
                null, null)

        when:
        TransferManager.downloadBlobToFile(outChannel, bu, null,
                new TransferManager.DownloadFromBlobOptions(null, null, bac, null,
                        null)).blockingGet()

        then:
        def e = thrown(StorageException)
        e.errorCode() == StorageErrorCode.CONDITION_NOT_MET ||
                e.errorCode() == StorageErrorCode.LEASE_ID_MISMATCH_WITH_BLOB_OPERATION

        where:
        modified | unmodified | match       | noneMatch    | leaseID
        newDate  | null       | null        | null         | null
        null     | oldDate    | null        | null         | null
        null     | null       | garbageEtag | null         | null
        null     | null       | null        | receivedEtag | null
        null     | null       | null        | null         | garbageLeaseID
    }

    def "Download file etag lock"() {
        setup:
        bu.upload(Flowable.just(getRandomData(1 * 1024 * 1024)), 1 * 1024 * 1024, null, null,
                null).blockingGet()
        def outChannel = AsynchronousFileChannel.open(getRandomFile(0).toPath(), StandardOpenOption.WRITE,
                StandardOpenOption.READ)

        when:
        /*
         Set up a large download in small chunks so it makes a lot of requests. This will give us time to cut in an
         operation that will change the etag.
         */
        def success = false
        TransferManager.downloadBlobToFile(outChannel, bu, null,
                new TransferManager.DownloadFromBlobOptions(1024, null, null,
                        null, null))
                .subscribe(
                new Consumer<BlobDownloadHeaders>() {
                    @Override
                    void accept(BlobDownloadHeaders headers) throws Exception {
                        success = false
                    }
                },
                new Consumer<Throwable>() {
                    @Override
                    void accept(Throwable throwable) throws Exception {
                        if (throwable instanceof StorageException &&
                                ((StorageException) throwable).statusCode() == 412) {
                            success = true
                            return
                        }
                        success = false
                    }
                })


        sleep(500) // Give some time for the download request to start.
        bu.upload(defaultFlowable, defaultDataSize, null, null, null).blockingGet()

        sleep(1000) // Allow time for the upload operation

        then:
        success

        cleanup:
        outChannel.close()
    }

    @Unroll
    def "Download file options"() {
        setup:
        def channel = AsynchronousFileChannel.open(getRandomFile(defaultDataSize).toPath(), StandardOpenOption.READ,
                StandardOpenOption.WRITE)
        TransferManager.uploadFileToBlockBlob(channel, bu, BlockBlobURL.MAX_STAGE_BLOCK_BYTES, null)
                .blockingGet()
        def outChannel = AsynchronousFileChannel.open(getRandomFile(0).toPath(), StandardOpenOption.WRITE,
                StandardOpenOption.READ)
        def retryReaderOptions = new RetryReaderOptions()
        retryReaderOptions.maxRetryRequests = retries

        when:
        TransferManager.downloadBlobToFile(outChannel, bu, null, new TransferManager.DownloadFromBlobOptions(
                blockSize, null, null, parallelism, retryReaderOptions)).blockingGet()

        then:
        compareFiles(channel, 0, channel.size(), outChannel)

        cleanup:
        channel.close()
        outChannel.close()

        where:
        blockSize | parallelism | retries
        1         | null        | 2
        null      | 1           | 2
        null      | null        | 1
    }

    @Unroll
    def "Download file IA null"() {
        when:
        TransferManager.downloadBlobToFile(file, blobURL, null, null).blockingGet()

        then:
        thrown(IllegalArgumentException)

        /*
        This test is just validating that exceptions are thrown if certain values are null. The values not being test do
        not need to be correct, simply not null. Because order in which Spock initializes values, we can't just use the
        bu property for the url.
         */
        where:
        file                                                     | blobURL
        null                                                     | new BlockBlobURL(new URL("http://account.com"), StorageURL.createPipeline(primaryCreds, new PipelineOptions()))
        AsynchronousFileChannel.open(getRandomFile(10).toPath()) | null
    }

    @Unroll
    def "Download options fail"() {
        when:
        new TransferManager.DownloadFromBlobOptions(blockSize, null, null, parallelism,
                null)

        then:
        thrown(IllegalArgumentException)

        where:
        parallelism | blockSize
        0           | 40
        2           | 0
    }
}

