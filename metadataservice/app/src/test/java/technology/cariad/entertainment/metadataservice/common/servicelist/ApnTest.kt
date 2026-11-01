package technology.cariad.entertainment.metadataservice.common.servicelist

import org.junit.Assert
import org.junit.Test
import de.esolutions.coreservices.csm.service.systemservice.Apn as ApnExt

class ApnTest {

    @Test
    fun mapServiceListApn_shouldMapKnownConstantsConsistently() {
        val vendor = mapServiceListApn(ApnExt.APN_VENDOR)
        val customer = mapServiceListApn(ApnExt.APN_CUSTOMER)
        val noEsim = mapServiceListApn(ApnExt.APN_NOESIM)
        val esimOnly = mapServiceListApn(ApnExt.APN_ESIMONLY)

        Assert.assertTrue(vendor in Apn.entries)
        Assert.assertTrue(customer in Apn.entries)
        Assert.assertTrue(noEsim in Apn.entries)
        Assert.assertTrue(esimOnly in Apn.entries)
    }

    @Test
    fun mapServiceListApn_shouldReturnKnownEnumForNotAllowedConstant() {
        Assert.assertTrue(mapServiceListApn(ApnExt.APN_NOTALLOWED) in Apn.entries)
    }

    @Test
    fun mapServiceListApn_shouldReturnNotAllowedForInvalidApn() {
        Assert.assertEquals(mapServiceListApn(-1), Apn.APN_NOT_ALLOWED)
        Assert.assertEquals(mapServiceListApn(999), Apn.APN_NOT_ALLOWED)
    }
}

