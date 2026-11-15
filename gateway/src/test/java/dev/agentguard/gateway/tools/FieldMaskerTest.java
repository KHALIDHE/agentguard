package dev.agentguard.gateway.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The same raw upstream record, and what each role's profile lets through. */
class FieldMaskerTest {

    private static final String RAW = """
        {"ID":42,"FULL_NAME":"Salma Tazi","TAX_ID":"ZZ100042","FOUNDED_ON":"1980-02-01",
         "PHONE":"0612345678","ADDRESS":"12 Rue Ibn Sina, Rabat","IBAN":"Hypertension",
         "ONBOARDED_ON":"2026-10-01","DEPARTMENT":"ACCT","MANAGER_ID":1,"MANAGER_NAME":"Omar Benali"}""";

    @Test
    void accountantLosesAddressAndIbanAndSeesOnlyTheEndOfIdentifiers() {
        String masked = FieldMasker.maskJson(RAW, "accountant-supplier");

        assertThat(masked).contains("\"FULL_NAME\":\"Salma Tazi\"")
            .contains("\"TAX_ID\":\"******42\"")
            .contains("\"PHONE\":\"********78\"")
            .doesNotContain("ADDRESS").doesNotContain("Ibn Sina")
            .doesNotContain("IBAN").doesNotContain("Hypertension");
    }

    @Test
    void clerkKeepsOnlyWhatBookingNeeds() {
        String masked = FieldMasker.maskJson(RAW, "clerk-supplier");

        assertThat(masked).contains("FULL_NAME").contains("DEPARTMENT").contains("MANAGER_ID")
            .doesNotContain("TAX_ID").doesNotContain("PHONE").doesNotContain("FOUNDED_ON")
            .doesNotContain("MANAGER_NAME").doesNotContain("IBAN");
    }

    @Test
    void listsAreMaskedRowByRow() {
        String masked = FieldMasker.maskJson("[" + RAW + "," + RAW + "]", "accountant-supplier");

        assertThat(masked).doesNotContain("Hypertension");
        assertThat(masked.split("\\*\\*\\*\\*\\*\\*42", -1)).hasSize(3);
    }

    @Test
    void anAllowListHidesColumnsAddedUpstreamLater() {
        String withNewColumn = RAW.replace("}", ",\"BLOOD_TYPE\":\"O-\"}");

        assertThat(FieldMasker.maskJson(withNewColumn, "clerk-supplier")).doesNotContain("BLOOD_TYPE");
    }

    @Test
    void textThatIsNotJsonCannotBeMasked() {
        assertThatThrownBy(() -> FieldMasker.maskJson("Supplier Salma, iban Hypertension", "accountant-supplier"))
            .isInstanceOf(RuntimeException.class);
    }
}
