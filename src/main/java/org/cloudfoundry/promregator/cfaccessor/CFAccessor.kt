package org.cloudfoundry.promregator.cfaccessor

import org.cloudfoundry.client.v2.applications.ApplicationResource
import org.cloudfoundry.client.v2.organizations.ListOrganizationsResponse
import org.cloudfoundry.client.v2.organizations.OrganizationResource
import org.cloudfoundry.client.v2.spaces.GetSpaceSummaryResponse
import org.cloudfoundry.client.v2.spaces.ListSpacesResponse
import org.cloudfoundry.client.v2.spaces.SpaceResource
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface CFAccessor {
    fun retrieveOrgId(api: String, orgName: String): Mono<ListOrganizationsResponse>
    fun retrieveAllOrgIds(api: String): Flux<OrganizationResource>
    fun retrieveSpaceId(api: String, orgId: String, spaceName: String): Mono<ListSpacesResponse>
    fun retrieveSpaceIdsInOrg(api: String, orgId: String): Flux<SpaceResource>
    fun retrieveAllApplicationIdsInSpace(api: String, orgId: String, spaceId: String): Flux<ApplicationResource>
    fun retrieveSpaceSummary(api: String, spaceId: String): Mono<GetSpaceSummaryResponse>
}
