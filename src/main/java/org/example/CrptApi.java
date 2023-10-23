package org.example;


import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {

    private static Queue<Instant> lastRequestTimeQueue;
    private final TimeUnit timeUnit;
    private Lock lock = new ReentrantLock(true);

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        lastRequestTimeQueue = new LinkedBlockingQueue<>(requestLimit);
    }

    public String createDocument(CreationDocumentData documentData, String signature) throws InterruptedException, IOException {
        lock.lock();
        while (!lastRequestTimeQueue.offer(Instant.now())) {
            waitUntilOldestGone();
        }
        lock.unlock();

        return executeDocumentCreationRequest(documentData, signature);
    }

    private void waitUntilOldestGone() throws InterruptedException {
        Instant oldestRequestRemovingTime = lastRequestTimeQueue.peek()
                .plus(1, timeUnit.toChronoUnit());
        Instant now = Instant.now();
        if (oldestRequestRemovingTime.isAfter(now)) {
            CountDownLatch latch = new CountDownLatch(1);
            Timer timer = new Timer();
            Duration duration = Duration.between(now, oldestRequestRemovingTime);
            timer.schedule(new RemoveOldTask(latch), duration.toMillis());
            latch.await();
        }
        cleanOldElements();
    }

    private void cleanOldElements() {
        //calculating moment before which we should remove elements
        Instant localDateTime = Instant.now().minus(1, timeUnit.toChronoUnit());

        //removing all old elements from queue
        while (!lastRequestTimeQueue.isEmpty() && lastRequestTimeQueue.peek().isBefore(localDateTime)) {
            lastRequestTimeQueue.remove();
        }
    }

    private static class RemoveOldTask extends TimerTask {
        private final CountDownLatch latch;

        private RemoveOldTask(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void run() {
            latch.countDown();
        }
    }

    private String executeDocumentCreationRequest(CreationDocumentData creationDocumentData, String signature) throws IOException, InterruptedException {
        //encode product document and signature to Base64
        String encodedProductDocument = Base64.getEncoder().encodeToString(creationDocumentData.getProductDocument().getBytes());
        String encodedSignature = Base64.getEncoder().encodeToString(signature.getBytes());

        //create request body
        DocumentCreationJSONRequestBody requestBody = new DocumentCreationJSONRequestBody();
        requestBody.setDocument_format(creationDocumentData.getDocumentType().getFormat());
        requestBody.setProduct_document(encodedProductDocument);
        requestBody.setProduct_group(creationDocumentData.getProductGroup().getCode());
        requestBody.setSignature(encodedSignature);
        requestBody.setType(creationDocumentData.getDocumentType().name());

        String jsonRequestBody = new Gson().toJson(requestBody);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create?pg=" + creationDocumentData.getProductGroup().name()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + creationDocumentData.getToken())
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequestBody))
                .build();
        //execute query
        HttpResponse<String> jsonResponse = client.send(request, HttpResponse.BodyHandlers.ofString());

        return jsonResponse.body();
    }

    //Input data class for creation a document
    public static class CreationDocumentData {

        private final String productDocument;  //maybe File?
        private final ProductGroup productGroup;
        private final DocumentType documentType;
        private final String token;

        public CreationDocumentData(String productDocument, ProductGroup productGroup, DocumentType documentType, String token) {
            this.productDocument = productDocument;
            this.productGroup = productGroup;
            this.documentType = documentType;
            this.token = token;
        }

        public String getProductDocument() {
            return productDocument;
        }

        public ProductGroup getProductGroup() {
            return productGroup;
        }

        public DocumentType getDocumentType() {
            return documentType;
        }

        public String getToken() {
            return token;
        }
    }

    //dependence of the Product Group on Product code
    public enum ProductGroup {

        clothes(1),
        shoes(2),
        tobacco(3),
        perfumery(4),
        tires(5),
        electronics(6),
        pharma(7),
        milk(8),
        bicycle(9),
        wheelchairs(10);

        private final int code;

        ProductGroup(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    //dependence of the Document format on its type
    public enum DocumentType {

        LP_INTRODUCE_GOODS("MANUAL"),  //Ввод в оборот, Производство РФ. json
        LP_INTRODUCE_GOODS_CSV("CSV"),  //Ввод в оборот, Производство РФ. csv
        LP_INTRODUCE_GOODS_XML("XML");  //Ввод в оборот, Производство РФ. xml

        private final String format;

        DocumentType(String format) {
            this.format = format;
        }

        public String getFormat() {
            return format;
        }
    }

    private class DocumentCreationJSONRequestBody {

        private String document_format;
        private String product_document;
        private int product_group;
        private String signature;
        private String type;

        public DocumentCreationJSONRequestBody setDocument_format(String document_format) {
            this.document_format = document_format;
            return this;
        }

        public DocumentCreationJSONRequestBody setProduct_document(String product_document) {
            this.product_document = product_document;
            return this;
        }

        public DocumentCreationJSONRequestBody setProduct_group(int product_group) {
            this.product_group = product_group;
            return this;
        }

        public DocumentCreationJSONRequestBody setSignature(String signature) {
            this.signature = signature;
            return this;
        }

        public DocumentCreationJSONRequestBody setType(String type) {
            this.type = type;
            return this;
        }
    }
}

