package nl.tudelft.ipv8.attestation.trustchain.validation

import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.attestation.trustchain.TrustChainStore

interface TransactionValidator {
    fun validate(block: TrustChainBlock, database: TrustChainStore): ValidationResult
}
