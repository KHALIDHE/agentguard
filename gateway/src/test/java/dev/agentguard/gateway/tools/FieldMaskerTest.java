package dev.agentguard.gateway.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The same raw upstream record, and what each role's profile lets through. */
class FieldMaskerTest {

    private static final String RAW = """
        {"ID":42,"FULL_NAME":"Salma Tazi","NATIONAL_ID":"ZZ100042","BIRTH_DATE":"1980-02-01",
         "PHONE":"0612345678","ADDRESS":"12 Rue Ibn Sina, Rabat","DIAGNOSIS":"Hypertension",
         "ADMITTED_ON":"2026-10-01","DEPARTMENT":"CARDIO","DOCTOR_ID":1,"DOCTOR_NAME":"Omar Benali"}""";

    @Test
    void nurseLosesAddressAndDiagnosisAndSeesOnlyTheEndOfIdentifiers() {
        String masked = FieldMasker.maskJson(RAW, "nurse-patient");

        assertThat(masked).contains("\"FULL_NAME\":\"Salma Tazi\"")
            .contains("\"NATIONAL_ID\":\"******42\"")
            .contains("\"PHONE\":\"********78\"")
            .doesNotContain("ADDRESS").doesNotContain("Ibn Sina")
            .doesNotContain("DIAGNOSIS").doesNotContain("Hypertension");
    }

    @Test
    void receptionKeepsOnlyWhatBookingNeeds() {
        String masked = FieldMasker.maskJson(RAW, "reception-patient");

        assertThat(masked).contains("FULL_NAME").contains("DEPARTMENT").contains("DOCTOR_ID")
            .doesNotContain("NATIONAL_ID").doesNotContain("PHONE").doesNotContain("BIRTH_DATE")
            .doesNotContain("DOCTOR_NAME").doesNotContain("DIAGNOSIS");
    }

    @Test
    void listsAreMaskedRowByRow() {
        String masked = FieldMasker.maskJson("[" + RAW + "," + RAW + "]", "nurse-patient");

        assertThat(masked).doesNotContain("Hypertension");
        assertThat(masked.split("\\*\\*\\*\\*\\*\\*42", -1)).hasSize(3);
    }

    @Test
    void anAllowListHidesColumnsAddedUpstreamLater() {
        String withNewColumn = RAW.replace("}", ",\"BLOOD_TYPE\":\"O-\"}");

        assertThat(FieldMasker.maskJson(withNewColumn, "reception-patient")).doesNotContain("BLOOD_TYPE");
    }

    @Test
    void textThatIsNotJsonCannotBeMasked() {
        assertThatThrownBy(() -> FieldMasker.maskJson("Patient Salma, diagnosis Hypertension", "nurse-patient"))
            .isInstanceOf(RuntimeException.class);
    }
}
