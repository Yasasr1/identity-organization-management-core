/*
 * Copyright (c) 2022-2025, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.organization.management.service;

import org.apache.commons.lang.StringUtils;
import org.wso2.carbon.identity.organization.management.service.dao.OrganizationManagementDAO;
import org.wso2.carbon.identity.organization.management.service.dao.impl.OrganizationManagementDAOImpl;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementServerException;
import org.wso2.carbon.identity.organization.management.service.internal.OrganizationManagementDataHolder;
import org.wso2.carbon.identity.organization.management.service.model.BasicOrganization;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.common.User;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_WHILE_RESOLVING_USERS_ROOT_ORG;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_WHILE_RESOLVING_USER_FROM_RESIDENT_ORG;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ERROR_WHILE_RESOLVING_USER_IN_RESIDENT_ORG;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_NO_USERNAME_OR_ID_TO_RESOLVE_USER_FROM_RESIDENT_ORG;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.SUPER_ORG_ID;
import static org.wso2.carbon.identity.organization.management.service.util.Utils.handleClientException;
import static org.wso2.carbon.identity.organization.management.service.util.Utils.handleServerException;
import static org.wso2.carbon.user.core.UserCoreConstants.DOMAIN_SEPARATOR;
import static org.wso2.carbon.user.core.UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME;

/**
 * Service implementation to resolve user's resident organization.
 */
public class OrganizationUserResidentResolverServiceImpl implements OrganizationUserResidentResolverService {

    private final OrganizationManagementDAO organizationManagementDAO = new OrganizationManagementDAOImpl();

    @Override
    public Optional<User> resolveUserFromResidentOrganization(String userName, String userId,
                                                              String accessedOrganizationId)
            throws OrganizationManagementException {

        String domain = null;

        try {
            if (StringUtils.isBlank(userName) && StringUtils.isBlank(userId)) {
                throw handleClientException(ERROR_CODE_NO_USERNAME_OR_ID_TO_RESOLVE_USER_FROM_RESIDENT_ORG);
            }
            if (StringUtils.isNotBlank(userName)) {
                domain = UserCoreUtil.extractDomainFromName(userName);
            }
            List<String> ancestorOrganizationIds =
                    organizationManagementDAO.getAncestorOrganizationIds(accessedOrganizationId);
            if (ancestorOrganizationIds != null) {
                for (String organizationId : ancestorOrganizationIds) {
                    String associatedTenantDomainForOrg = resolveTenantDomainForOrg(organizationId);
                    if (StringUtils.isNotBlank(associatedTenantDomainForOrg)) {
                        AbstractUserStoreManager userStoreManager = getUserStoreManager(associatedTenantDomainForOrg);
                        boolean isValidDomain = StringUtils.isNotBlank(domain) &&
                                userStoreManager.getSecondaryUserStoreManager(domain) != null;
                        if (StringUtils.isNotBlank(userName) && isValidDomain &&
                                userStoreManager.isExistingUser(userName)) {
                            return Optional.of(userStoreManager.getUser(null, userName));
                        } else if (StringUtils.isNotBlank(userId) && userStoreManager.isExistingUserWithID(userId)) {
                            return Optional.of(userStoreManager.getUser(userId, null));
                        } else if (StringUtils.isNotBlank(userName) &&
                                UserCoreUtil.removeDomainFromName(userName).equals(userName)) {
                            /*
                              Try to find the user from the secondary user stores when the username is not domain
                              qualified.
                             */
                            UserStoreManager secondaryUserStoreManager =
                                    userStoreManager.getSecondaryUserStoreManager();
                            while (secondaryUserStoreManager != null) {
                                domain = secondaryUserStoreManager.getRealmConfiguration().getUserStoreProperties()
                                        .get(PROPERTY_DOMAIN_NAME);
                                if (userStoreManager.isExistingUser(domain + DOMAIN_SEPARATOR + userName)) {
                                    return Optional.of(
                                            userStoreManager.getUser(null, domain + DOMAIN_SEPARATOR + userName));
                                }
                                secondaryUserStoreManager = secondaryUserStoreManager.getSecondaryUserStoreManager();
                            }
                        }
                    }
                }
            }
        } catch (UserStoreException | OrganizationManagementServerException e) {
            throw handleServerException(ERROR_CODE_ERROR_WHILE_RESOLVING_USER_FROM_RESIDENT_ORG, e, userName,
                    accessedOrganizationId);
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> resolveResidentOrganization(String userId, String accessedOrganizationId)
            throws OrganizationManagementException {

        String residentOrgId = null;
        try {
            List<String> ancestorOrganizationIds =
                    organizationManagementDAO.getAncestorOrganizationIds(accessedOrganizationId);
            if (ancestorOrganizationIds != null) {
                for (String organizationId : ancestorOrganizationIds) {
                    String associatedTenantDomainForOrg = resolveTenantDomainForOrg(organizationId);
                    if (StringUtils.isBlank(associatedTenantDomainForOrg)) {
                        continue;
                    }
                    AbstractUserStoreManager userStoreManager = getUserStoreManager(associatedTenantDomainForOrg);
                    if (userStoreManager.isExistingUserWithID(userId)) {
                        residentOrgId = organizationId;
                        /*
                            User resident organization logic should be improved based on the user store configurations
                            in the deployment. So commenting the flow break as a temporary fix.
                         */
                        //break;
                    }
                }
            }
        } catch (UserStoreException e) {
            throw handleServerException(ERROR_CODE_ERROR_WHILE_RESOLVING_USERS_ROOT_ORG, e, userId);
        }
        return Optional.ofNullable(residentOrgId);
    }

    @Override
    public List<BasicOrganization> getHierarchyUptoResidentOrganization
            (String userId, String accessedOrganizationId) throws OrganizationManagementException {

        String residentOrgId = null;
        List<BasicOrganization> basicOrganizationList = new ArrayList<>();
        try {
            List<String> ancestorOrganizationIds =
                    organizationManagementDAO.getAncestorOrganizationIds(accessedOrganizationId);
            if (ancestorOrganizationIds != null) {
                for (String organizationId : ancestorOrganizationIds) {
                    String associatedTenantDomainForOrg = resolveTenantDomainForOrg(organizationId);
                    if (StringUtils.isBlank(associatedTenantDomainForOrg)) {
                        continue;
                    }
                    Optional<String> organizationName = organizationManagementDAO
                            .getOrganizationNameById(organizationId);
                    if (organizationName.isPresent()) {
                        BasicOrganization basicOrganization = new BasicOrganization();
                        basicOrganization.setId(organizationId);
                        basicOrganization.setName(organizationName.get());
                        basicOrganization.setOrganizationHandle(associatedTenantDomainForOrg);
                        basicOrganizationList.add(basicOrganization);
                    }
                    AbstractUserStoreManager userStoreManager = getUserStoreManager(associatedTenantDomainForOrg);
                    if (userStoreManager.isExistingUserWithID(userId)) {
                        residentOrgId = organizationId;
                        /*
                            User resident organization logic should be improved based on the user store configurations
                            in the deployment. So commenting the flow break as a temporary fix.
                         */
                        //break;
                    }
                }
            }
        } catch (UserStoreException e) {
            throw handleServerException(ERROR_CODE_ERROR_WHILE_RESOLVING_USERS_ROOT_ORG, e, userId);
        }
        /*
        Organizations will be sorted starting from resident organization (higher level) and ended up with
        the accessed organization (lower level)
         */
        if (residentOrgId == null) {
            return new ArrayList<>();
        }
        int residentOrgIndex = basicOrganizationList.stream().map(BasicOrganization::getId)
                .collect(Collectors.toList()).indexOf(residentOrgId);
        basicOrganizationList = basicOrganizationList.subList(0, residentOrgIndex + 1);
        Collections.reverse(basicOrganizationList);
        return basicOrganizationList;
    }

    public Optional<String> getUserNameFromResidentOrgId(String userId, String userResidentOrganizationId)
            throws OrganizationManagementException {

        String username = null;
        try {
            String associatedTenantDomainForOrg = resolveTenantDomainForOrg(userResidentOrganizationId);
            if (StringUtils.isNotBlank(associatedTenantDomainForOrg)) {
                AbstractUserStoreManager userStoreManager = getUserStoreManager(associatedTenantDomainForOrg);
                username = userStoreManager.getUser(userId, null).getUsername();
            }
        } catch (UserStoreException | OrganizationManagementServerException e) {
            throw handleServerException(ERROR_CODE_ERROR_WHILE_RESOLVING_USER_IN_RESIDENT_ORG, e,
                    userId, userResidentOrganizationId);
        }
        return Optional.ofNullable(username);
    }

    private String resolveTenantDomainForOrg(String organizationId) throws OrganizationManagementServerException {

        if (StringUtils.equals(SUPER_ORG_ID, organizationId)) {
            // super tenant domain will be returned.
            return MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
        }
        return organizationManagementDAO.resolveTenantDomain(organizationId);
    }

    private AbstractUserStoreManager getUserStoreManager(String tenantDomain) throws UserStoreException {

        int tenantId = OrganizationManagementDataHolder.getInstance().getRealmService().getTenantManager()
                .getTenantId(tenantDomain);
        RealmService realmService = OrganizationManagementDataHolder.getInstance().getRealmService();
        UserRealm tenantUserRealm = realmService.getTenantUserRealm(tenantId);
        return (AbstractUserStoreManager) tenantUserRealm.getUserStoreManager();
    }
}
