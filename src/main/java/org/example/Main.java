package org.example;

public class Main {
    public static void main(String[] args) {
        CrptApi api = new CrptApi(
                "https://ismp.crpt.ru/api/v3",
                CrptApi.TimeUnit.MINUTES,
                10
        );

        try {
            CrptApi.AuthKeyResponse authKey = api.getAuthKey();
            System.out.println("UUID: " + authKey.uuid);
            System.out.println("Data to sign: " + authKey.data);

            String signedData = signData(authKey.data);
            System.out.println("Signed data: " + signedData);

            CrptApi.AuthTokenResponse tokenResponse = api.getAuthToken(authKey.uuid, signedData);
            String authToken = tokenResponse.authToken;
            System.out.println("Auth token: " + authToken);

            CrptApi.IntroduceGoodsDocument doc = new CrptApi.IntroduceGoodsDocument();
            doc.participantInn = "7701234567";
            doc.ownerInn = "7701234567";
            doc.producerInn = "7701234567";
            doc.productionDate = "2025-09-17";
            doc.certificateDocument = "CERT";
            doc.certificateDocumentDate = "2025-09-01";
            doc.certificateDocumentNumber = "123456";

            String docSignature = "fake-signature";

            String response = api.createIntroduceGoodsDocument(doc, docSignature, authToken);
            System.out.println("Response from ЧЗ: " + response);

        } catch (CrptApi.ApiException e) {
            System.err.println("Ошибка при работе с ЧЗ: " + e.getMessage());
        }
    }

    private static String signData(String data) {
        return "signed_" + data;
    }
}
