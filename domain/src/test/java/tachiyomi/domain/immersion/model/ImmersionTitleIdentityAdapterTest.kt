package tachiyomi.domain.immersion.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class ImmersionTitleIdentityAdapterTest {
    @Test
    fun `native identities preserve capture adapter UUID compatibility`() {
        ImmersionTitleIdentityAdapter.manga(42, "jp").id.value shouldBe
            "9ce8a956-05c5-3860-8abc-0a20ae4ae245"
        ImmersionTitleIdentityAdapter.novel("document-42", "jp").id.value shouldBe
            "35f560f7-4df7-3474-80b5-a94bb28eed5c"
        ImmersionTitleIdentityAdapter.video(42, "jp").id.value shouldBe
            "fe4c54f5-179c-32c1-911f-229968d34c96"
    }

    @Test
    fun `identity depends on immutable source and profile rather than display metadata`() {
        val first = ImmersionTitleIdentityAdapter.manga(42, "jp")
        val sameAfterLocalDeletion = ImmersionTitleIdentityAdapter.manga(42, "jp")
        val otherSourceWithSameDisplayTitle = ImmersionTitleIdentityAdapter.video(42, "jp")
        val otherProfile = ImmersionTitleIdentityAdapter.manga(42, "en")

        sameAfterLocalDeletion shouldBe first
        otherSourceWithSameDisplayTitle.id shouldNotBe first.id
        otherProfile.id shouldNotBe first.id
        first.sourceKey shouldBe "manga:42"
        first.libraryId shouldBe 42
        first.mediaId shouldBe "42"
    }

    @Test
    fun `legacy identities preserve importer UUID compatibility`() {
        val identity = ImmersionTitleIdentityAdapter.legacy(
            mediaKind = MediaKind.NOVEL,
            sourceKey = "legacy:novel:book-42",
            profileId = "jp",
        )

        identity.id.value shouldBe "8eaaf4d8-8970-589d-b5cb-99652ec3f3a4"
        identity.sourceKey shouldBe "legacy:novel:book-42"
        identity.libraryId shouldBe null
    }

    @Test
    fun `invalid source identities are rejected`() {
        shouldThrow<IllegalArgumentException> {
            ImmersionTitleIdentityAdapter.manga(-1, "")
        }
        shouldThrow<IllegalArgumentException> {
            ImmersionTitleIdentityAdapter.novel("", "")
        }
        shouldThrow<IllegalArgumentException> {
            ImmersionTitleIdentityAdapter.legacy(MediaKind.NOVEL, "", "")
        }
    }
}
