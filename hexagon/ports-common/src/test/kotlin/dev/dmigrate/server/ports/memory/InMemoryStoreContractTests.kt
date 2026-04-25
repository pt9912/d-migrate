package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.ports.contract.ApprovalGrantStoreContractTests
import dev.dmigrate.server.ports.contract.ArtifactContentStoreContractTests
import dev.dmigrate.server.ports.contract.ArtifactStoreContractTests
import dev.dmigrate.server.ports.contract.AuditSinkContractTests
import dev.dmigrate.server.ports.contract.ConnectionReferenceStoreContractTests
import dev.dmigrate.server.ports.contract.DiffStoreContractTests
import dev.dmigrate.server.ports.contract.IdempotencyStoreContractTests
import dev.dmigrate.server.ports.contract.JobStoreContractTests
import dev.dmigrate.server.ports.contract.ProfileStoreContractTests
import dev.dmigrate.server.ports.contract.QuotaStoreContractTests
import dev.dmigrate.server.ports.contract.ReadOnlyInitResumeContractTests
import dev.dmigrate.server.ports.contract.SchemaStoreContractTests
import dev.dmigrate.server.ports.contract.SyncEffectIdempotencyStoreContractTests
import dev.dmigrate.server.ports.contract.UploadSegmentStoreContractTests
import dev.dmigrate.server.ports.contract.UploadSessionStoreContractTests

class InMemoryJobStoreContractTest : JobStoreContractTests({ InMemoryJobStore() })

class InMemoryArtifactStoreContractTest : ArtifactStoreContractTests({ InMemoryArtifactStore() })

class InMemoryArtifactContentStoreContractTest :
    ArtifactContentStoreContractTests({ InMemoryArtifactContentStore() })

class InMemorySchemaStoreContractTest : SchemaStoreContractTests({ InMemorySchemaStore() })

class InMemoryProfileStoreContractTest : ProfileStoreContractTests({ InMemoryProfileStore() })

class InMemoryDiffStoreContractTest : DiffStoreContractTests({ InMemoryDiffStore() })

class InMemoryUploadSessionStoreContractTest :
    UploadSessionStoreContractTests({ InMemoryUploadSessionStore() })

class InMemoryUploadSegmentStoreContractTest :
    UploadSegmentStoreContractTests({ InMemoryUploadSegmentStore() })

class InMemoryConnectionReferenceStoreContractTest :
    ConnectionReferenceStoreContractTests({ InMemoryConnectionReferenceStore() })

class InMemoryIdempotencyStoreContractTest :
    IdempotencyStoreContractTests({ InMemoryIdempotencyStore() })

class InMemorySyncEffectIdempotencyStoreContractTest :
    SyncEffectIdempotencyStoreContractTests({ InMemorySyncEffectIdempotencyStore() })

class InMemoryReadOnlyInitResumeContractTest :
    ReadOnlyInitResumeContractTests({ InMemoryIdempotencyStore() })

class InMemoryApprovalGrantStoreContractTest :
    ApprovalGrantStoreContractTests({ InMemoryApprovalGrantStore() })

class InMemoryAuditSinkContractTest : AuditSinkContractTests({ InMemoryAuditSink() })

class InMemoryQuotaStoreContractTest : QuotaStoreContractTests({ InMemoryQuotaStore() })
