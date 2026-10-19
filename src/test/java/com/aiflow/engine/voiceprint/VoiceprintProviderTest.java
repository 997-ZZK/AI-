package com.aiflow.engine.voiceprint;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceprintProviderTest {

    @Test
    void disabledProviderIsExplicitlyUnavailableForEveryLifecycleOperation() {
        VoiceprintProvider provider = VoiceprintProvider.disabled();

        assertThat(provider.id()).isEqualTo("disabled");
        assertThat(provider.available()).isFalse();
        assertThat(provider.metadata().capabilities()).isEmpty();
        assertThat(provider.enroll("subject-1", new VoiceprintSample(new byte[]{1}, "audio/wav")))
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo(VoiceprintEnrollment.Status.UNAVAILABLE);
                    assertThat(result.reason()).isEqualTo(VoiceprintEnrollment.Reason.DISABLED);
                });
        assertThat(provider.verify("subject-1", new VoiceprintSample(new byte[]{1}, "audio/wav")))
                .satisfies(result -> {
                    assertThat(result.outcome()).isEqualTo(VoiceprintVerification.Outcome.UNAVAILABLE);
                    assertThat(result.reason()).isEqualTo(VoiceprintVerification.Reason.DISABLED);
                    assertThat(result.confidence()).isNull();
                });
        assertThat(provider.delete("subject-1"))
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo(VoiceprintDeletion.Status.UNAVAILABLE);
                    assertThat(result.reason()).isEqualTo(VoiceprintDeletion.Reason.DISABLED);
                });
    }

    @Test
    void sampleUsesDefensiveCopiesAndDoesNotEchoBytes() {
        byte[] source = new byte[]{1, 2, 3};
        VoiceprintSample sample = new VoiceprintSample(source, " AUDIO/WAV ");

        source[0] = 9;
        byte[] returned = sample.bytes();
        returned[1] = 9;

        assertThat(sample.bytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(sample.mediaType()).isEqualTo("audio/wav");
        assertThat(sample.toString()).contains("byteLength=3").doesNotContain("1, 2, 3");
        assertThat(new VoiceprintSample(null, null).usable()).isFalse();
    }

    @Test
    void verificationOutcomesKeepFixedReasonsAndConfidenceShape() {
        VoiceprintConfidenceRange confidence = new VoiceprintConfidenceRange(0.72, 0.91);

        assertThat(VoiceprintVerification.verified(confidence))
                .isEqualTo(new VoiceprintVerification(VoiceprintVerification.Outcome.VERIFIED,
                        VoiceprintVerification.Reason.MATCH, confidence));
        assertThat(VoiceprintVerification.rejected(VoiceprintVerification.Reason.NO_MATCH, confidence).outcome())
                .isEqualTo(VoiceprintVerification.Outcome.REJECTED);
        assertThat(VoiceprintVerification.rejected(VoiceprintVerification.Reason.SUBJECT_NOT_ENROLLED, null)
                .confidence()).isNull();
        assertThat(VoiceprintVerification.unavailable(VoiceprintVerification.Reason.PROVIDER_UNAVAILABLE).confidence())
                .isNull();
        assertThat(VoiceprintVerification.invalidSample())
                .extracting(VoiceprintVerification::outcome, VoiceprintVerification::reason)
                .containsExactly(VoiceprintVerification.Outcome.INVALID_SAMPLE,
                        VoiceprintVerification.Reason.INVALID_SAMPLE);
    }

    @Test
    void verificationRejectsInvalidReasonAndConfidenceCombinations() {
        VoiceprintConfidenceRange confidence = VoiceprintConfidenceRange.exact(0.5);

        assertThatThrownBy(() -> new VoiceprintVerification(
                VoiceprintVerification.Outcome.VERIFIED, VoiceprintVerification.Reason.MATCH, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoiceprintVerification.rejected(
                VoiceprintVerification.Reason.DISABLED, confidence))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoiceprintVerification.rejected(
                VoiceprintVerification.Reason.NO_MATCH, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoiceprintVerification.rejected(
                VoiceprintVerification.Reason.SUBJECT_NOT_ENROLLED, confidence))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoiceprintVerification.unavailable(VoiceprintVerification.Reason.NO_MATCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confidenceRangeIsNormalizedToProbabilityBounds() {
        assertThat(VoiceprintConfidenceRange.exact(0.4).lowerInclusive()).isEqualTo(0.4);
        assertThatThrownBy(() -> new VoiceprintConfidenceRange(-0.01, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoiceprintConfidenceRange(0.4, 1.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoiceprintConfidenceRange(0.8, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataIsNonSensitiveAndDefensivelyCopied() {
        VoiceprintProviderMetadata metadata = new VoiceprintProviderMetadata(
                "local-sidecar", "model-v1", Set.of(VoiceprintProviderMetadata.VERIFY_ONE_TO_ONE));

        assertThat(metadata.providerId()).isEqualTo("local-sidecar");
        assertThat(metadata.modelVersion()).isEqualTo("model-v1");
        assertThat(metadata.capabilities()).containsExactly(VoiceprintProviderMetadata.VERIFY_ONE_TO_ONE);
        assertThat(VoiceprintProviderMetadata.defaultFor("mock").capabilities())
                .containsExactlyInAnyOrder(VoiceprintProviderMetadata.VERIFY_ONE_TO_ONE,
                        VoiceprintProviderMetadata.ENROLL, VoiceprintProviderMetadata.DELETE);
        assertThatThrownBy(() -> new VoiceprintProviderMetadata(" ", null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enrollmentAndDeletionResultsDoNotLeakTemplateReference() {
        VoiceprintEnrollment enrolled = VoiceprintEnrollment.enrolled("opaque-template-ref");
        assertThat(enrolled.status()).isEqualTo(VoiceprintEnrollment.Status.ENROLLED);
        assertThat(enrolled.templateReference()).isEqualTo("opaque-template-ref");
        assertThat(enrolled.toString()).doesNotContain("opaque-template-ref");
        assertThat(VoiceprintEnrollment.invalidSample().status())
                .isEqualTo(VoiceprintEnrollment.Status.INVALID_SAMPLE);
        assertThat(VoiceprintDeletion.deleted().status()).isEqualTo(VoiceprintDeletion.Status.DELETED);
        assertThat(VoiceprintDeletion.notFound().status()).isEqualTo(VoiceprintDeletion.Status.NOT_FOUND);
    }

    @Test
    void subjectIdMustBeNonBlankAndIsTrimmedBySharedBoundaryHelper() {
        assertThat(VoiceprintProvider.requireSubjectId(" subject-1 ")).isEqualTo("subject-1");
        assertThatThrownBy(() -> VoiceprintProvider.requireSubjectId(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoiceprintProvider.disabled().verify(" ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
