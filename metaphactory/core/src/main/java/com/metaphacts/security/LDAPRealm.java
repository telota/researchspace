/*
 * Copyright (C) 2015-2017, metaphacts GmbH
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, you can receive a copy
 * of the GNU Lesser General Public License from http://www.gnu.org/
 */

package com.metaphacts.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.authz.SimpleRole;
import org.apache.shiro.realm.activedirectory.ActiveDirectoryRealm;
import org.apache.shiro.realm.ldap.JndiLdapRealm;
import org.apache.shiro.realm.ldap.LdapContextFactory;
import org.apache.shiro.realm.ldap.LdapUtils;
import org.apache.shiro.subject.PrincipalCollection;

import com.google.common.collect.Sets;

/**
 * Extends the {@link JndiLdapRealm} to enable custom mappings from full
 * qualified LDAP groups to roles. Permissions will be read from the
 * {@link ShiroTextRealm}.
 *
 *
 * @author Johannes Trame <jt@metaphacts.com>
 */
@SuppressWarnings("deprecation")
public class LDAPRealm extends JndiLdapRealm implements UserMetadataProvider {

    private static final Logger logger = LogManager.getLogger(LDAPRealm.class);

    /**
     * Delimiter according to which the role names specified in the
     * groupRolesMap should be split
     */
    private static final String ROLE_NAMES_DELIMETER = ",";

    private static final SearchControls SUBTREE_SCOPE = new SearchControls();

    static {
        SUBTREE_SCOPE.setSearchScope(SearchControls.SUBTREE_SCOPE);
    }

    public LDAPRealm(){
        super();
    }

    /**
     * The DN string which identifies the root node for user searches
     * For example "dc=example,dc=com"
     */
    private String searchBase;

    /**
     * The DN string which identifies the root node for group searches.
     */
    private String groupSearchBase;

	/**
     * ObjectClass which identifies user object in the directory.
     */
    private String userObjectClass = "person";

    /**
     * Attribute to identify the unique id for a user object
     */
    private String userIdentifierAttribute = "uid";

    /**
     * Attribute that connects a LDAP group object to a member i.e. userDN
     * Common configuration is "member"
     */
    private String groupMemberAttribute = "member";

    /**
     * Attribute to identify the unique id for a group object
     */
    private String groupIdentifierAttribute= "ou";

    /**
     * Mapping from fully qualified active directory group names
     * (e.g. ou=mathematicians,dc=example,dc=com) to role names.
     *
     * Alternatively a shortcut version is to use the unique value
     * as identified by the groupIdentifierAttribute
     * (e.g. mathematicans)
     */
    private Map<String, String> groupRolesMap;

    public void setGroupRolesMap(Map<String, String> groupRolesMap) {
        this.groupRolesMap = groupRolesMap;
    }

    public String getSearchBase() {
        return searchBase;
    }

    public void setSearchBase(String searchBase) {
        this.searchBase = searchBase;
    }

    public String getGroupSearchBase() {
        return groupSearchBase == null ? this.getSearchBase() : groupSearchBase;
    }

    public void setGroupSearchBase(String groupSearchBase) {
        this.groupSearchBase = groupSearchBase;
    }

    public String getGroupMemberAttribute() {
        return groupMemberAttribute;
    }

    public void setGroupMemberAttribute(String groupMemberAttribute) {
        this.groupMemberAttribute = groupMemberAttribute;
    }

    public String getGroupIdentifierAttribute() {
        return groupIdentifierAttribute;
    }

    public void setGroupIdentifierAttribute(String groupIdentifierAttribute) {
        this.groupIdentifierAttribute = groupIdentifierAttribute;
    }

    public String getUserObjectClass() {
        return userObjectClass;
    }

    public void setUserObjectClass(String userObjectClass) {
        this.userObjectClass = userObjectClass;
    }

    public String getUserIdentifierAttribute() {
        return userIdentifierAttribute;
    }

    public void setUserIdentifierAttribute(String userIdAttribute) {
        this.userIdentifierAttribute = userIdAttribute;
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        AuthorizationInfo cachedAuthorization = getAuthorizationCache().get(getAuthorizationCacheKey(principals));
        if(cachedAuthorization!=null)
            return cachedAuthorization;

        AuthorizationInfo authorizationInfo = super.doGetAuthorizationInfo(principals);
        getAuthorizationCache().put(getAuthorizationCacheKey(principals), authorizationInfo);
        return authorizationInfo;
    }

    /**
     * Enable authorization cache by default
     * @see org.apache.shiro.realm.AuthorizingRealm#isAuthorizationCachingEnabled()
     */
    @Override
    public boolean isAuthorizationCachingEnabled() {
        return true;
    }

     /**
     * @see org.apache.shiro.realm.ldap.JndiLdapRealm#queryForAuthorizationInfo(org.apache.shiro.subject.PrincipalCollection, org.apache.shiro.realm.ldap.LdapContextFactory)
     */
    @Override
    protected AuthorizationInfo queryForAuthorizationInfo(
            PrincipalCollection principals,
            LdapContextFactory ldapContextFactory) throws NamingException {
         String username = (String) getAvailablePrincipal(principals);

         // Perform context search
         LdapContext ldapContext = ldapContextFactory.getSystemLdapContext();

         Set<String> roleNames;

         try {
             roleNames = getRoleNamesForUser(username, ldapContext);
         } finally {
             LdapUtils.closeContext(ldapContext);
         }

         SimpleAuthorizationInfo authorizationInfo = new SimpleAuthorizationInfo(roleNames);
         ShiroTextRealm shiroTextRealm = ((MetaphactsSecurityManager) SecurityUtils.getSecurityManager()).getShiroTextRealm();
         Map<String, SimpleRole> roles = shiroTextRealm.getRoles();
         Set<Permission>permissions = Sets.newHashSet();
         for(String roleString : authorizationInfo.getRoles()){
             if(roles.containsKey(roleString)){
                 permissions.addAll(roles.get(roleString).getPermissions());
             }
         }

         authorizationInfo.setObjectPermissions(permissions);
         return  authorizationInfo;
     }


    /**
     * With minor modifications taken from {@link ActiveDirectoryRealm} . Will
     * return empty set if user dn does not exist. This is particularly
     * important when being used in combination with
     * {@link com.metaphacts.security.AnonymousUserFilter}.
     * 
     * @param username
     * @param ldapContext
     * @return
     * @throws NamingException
     */
    private Set<String> getRoleNamesForUser(String username, LdapContext ldapContext) throws NamingException {
         Set<String> roleNames = new LinkedHashSet<String>();

         Optional<String> userDn = retrieveUserDn(username);
         if(!userDn.isPresent()) {
            /*
             * Code should not throw new
             * javax.naming.AuthenticationException("User DN not found.");
             * because shiro doesn't expect exceptions while iterating over
             * realms when checking user permissions. Instead return empty set
             * of role names.
             */
            return roleNames;
         }

         String[] returnAttributes = { groupIdentifierAttribute };
         SearchControls cons = new SearchControls();
         cons.setSearchScope(SearchControls.SUBTREE_SCOPE);
         cons.setReturningAttributes(returnAttributes);
         String searchFilter = "({0}={1})";
         Object[] searchArguments = new Object[]{groupMemberAttribute, userDn.get()};

         // use this only for logger
         // SHIRO-115 - prevent potential code injection:
         String searchFilterString = String.format("(%1$s=%2$s)", searchArguments);
         logger.debug("Searching for groups DN of user [{}], with filter: {}", userDn.get(), searchFilterString);

         NamingEnumeration<SearchResult> searchResults = ldapContext.search(this.getGroupSearchBase(), searchFilter, searchArguments, cons);

         if(!searchResults.hasMoreElements()){
             logger.warn("LDAP query did not return any groups for user [{}]", userDn.get());
         }

         while (searchResults.hasMoreElements()) {
             SearchResult sr = (SearchResult) searchResults.next();

             logger.debug("Retrieving group names for user [{}]", sr.getName());

             Attributes attrs = sr.getAttributes();

             if (attrs != null) {
                 NamingEnumeration<? extends Attribute> ae = attrs.getAll();
                 while (ae.hasMore()) {
                     Attribute attr = (Attribute) ae.next();

                     if (attr.getID().equals(groupIdentifierAttribute)) {

                         Collection<String> groupNames = LdapUtils.getAllAttributeValues(attr);
                         // TODO by now we adding both the full qualified group name as well as the UDI as determined by the groupIdentifierAttribute
                         groupNames.add(sr.getNameInNamespace());

                         if (logger.isDebugEnabled()) {
                             logger.debug("Groups found for user [{}]: {}", username, groupNames);
                         }

                         Collection<String> rolesForGroups = getRoleNamesForGroups(groupNames);
                         roleNames.addAll(rolesForGroups);
                     }
                 }
             }
         }
         return roleNames;
     }

    @Override
    protected AuthenticationInfo queryForAuthenticationInfo(AuthenticationToken token,
            LdapContextFactory ldapContextFactory) throws NamingException {
        Object principal = token.getPrincipal();
        Object credentials = token.getCredentials();

        logger.debug("Authenticating user '{}' through LDAP", principal);

        Optional<String> userDn = retrieveUserDn((String)principal);
        if(userDn.isPresent()) {
            principal = userDn.get();
            LdapContext ctx = null;
            try {
                ctx = ldapContextFactory.getLdapContext(principal, credentials);
                //context was opened successfully, which means their credentials were valid.  Return the AuthenticationInfo:
                return createAuthenticationInfo(token, principal, credentials, ctx);
            } finally {
                LdapUtils.closeContext(ctx);
            }
        } else {
            throw new javax.naming.AuthenticationException("User DN not found.");
        }
    }

    /**
     * Search for a user DN with ObjectClass corresponding to {@link #userObjectClass} and
     * principal matching {@link #userIdentifierAttribute}.
     */
    protected Optional<String> retrieveUserDn(String principal) throws NamingException {
        String searchFilter = "(&(objectclass={0})({1}={2}))";
        Object[] searchArguments = new Object[]{userObjectClass, userIdentifierAttribute,principal};

        // use this only for logger
        // SHIRO-115 - prevent potential code injection:
        String searchFilterString = String.format("(&(objectclass=%1$s)(%2$s=%3$s))", searchArguments);
        logger.debug("Searching for user DN, with filter: {}", searchFilterString);

        NamingEnumeration<SearchResult> searchResultEnum = null;
        LdapContext context = null;
        try {
            context = getContextFactory().getSystemLdapContext();
            searchResultEnum = context.search(searchBase, searchFilter, searchArguments, SUBTREE_SCOPE);
            if (searchResultEnum.hasMore()) {
                SearchResult searchResult = searchResultEnum.next();
                String dn = searchResult.getNameInNamespace();
                logger.debug("Found DN for the user: {}", dn);
                return Optional.of(dn);
            } else {
                logger.warn("User {} was not found in LDAP directory.", principal);
                return Optional.empty();
            }

        } finally {
            LdapUtils.closeEnumeration(searchResultEnum);
            LdapUtils.closeContext(context);
        }
    }

    private List<SearchResult> toListOfSearchResults(NamingEnumeration<SearchResult> searchResults) throws NamingException {
        List<SearchResult> result = new ArrayList<SearchResult>();
        while (searchResults.hasMore()) {
            SearchResult searchResult = searchResults.next();
            result.add(searchResult);
        }
        return result;
    }

    private List<String> getAttributeValues(SearchResult searchResult, String attributeName) throws NamingException {
        List<String> result = new ArrayList<String>();
        Attributes attrs = searchResult.getAttributes();
        if (attrs != null) {
            NamingEnumeration<? extends Attribute> ae = attrs.getAll();
            while (ae.hasMore()) {
                Attribute attr = (Attribute) ae.next();
                if (attr.getID().equals(attributeName)) {
                    Collection<String> values = LdapUtils.getAllAttributeValues(attr);
                    for (String value : values) {
                        result.add(value);
                    }
                }
            }
        }
        return result;
    }

    private List<SearchResult> getUsers(LdapContext ldapContext) throws NamingException {
        SearchControls cons = new SearchControls();
        cons.setSearchScope(SearchControls.SUBTREE_SCOPE);
        cons.setReturningAttributes(new String[] {userIdentifierAttribute});
        NamingEnumeration<SearchResult> searchResults = ldapContext.search(
            searchBase,
            "(objectclass={0})",
            new String[] {userObjectClass},
            cons
        );
        return toListOfSearchResults(searchResults);
    }

    private List<SearchResult> getGroupsForUser(LdapContext ldapContext, String userDN) throws NamingException {
        SearchControls cons = new SearchControls();
        cons.setSearchScope(SearchControls.SUBTREE_SCOPE);
        cons.setReturningAttributes(new String[]{groupIdentifierAttribute});
        NamingEnumeration<SearchResult> searchResults = ldapContext.search(
            this.getGroupSearchBase(),
            "({0}={1})",
            new Object[]{groupMemberAttribute, userDN},
            cons
        );
        return toListOfSearchResults(searchResults);
    }

    @Override
    public List<UserMetadata> getUsersMetadata() {
        List<UserMetadata> result = new ArrayList<UserMetadata>();
        try {
            LdapContext ldapContext = getContextFactory().getSystemLdapContext();

            List<SearchResult> users = getUsers(ldapContext);
            for (SearchResult user : users) {
                List<String> userUids = getAttributeValues(user, userIdentifierAttribute);
                for (String userUid : userUids) {
                    Set<UserMetadata.GroupProps> groupSet = new HashSet<>();
                    Set<String> roleSet = new HashSet<>();
                    List<SearchResult> groups = getGroupsForUser(ldapContext, user.getNameInNamespace());
                    for (SearchResult group : groups) {
                        groupSet.add(
                            new UserMetadata.GroupProps(
                                group.getNameInNamespace(),
                                group.getAttributes().get(groupIdentifierAttribute).get().toString()
                           )
                        );
                        String groupDn = group.getNameInNamespace();
                        Collection<String> groupList = Arrays.asList(groupDn);
                        for (String role : getRoleNamesForGroups(groupList)) {
                            roleSet.add(role);
                        }
                    }
                    result.add(new UserMetadata(userUid, groupSet, roleSet));
                }
            }
        } catch (NamingException e) { }
        return result;
    }


     /**
      * Mainly adapted from @see {@link ActiveDirectoryRealm}
      *
      * This method is called by the default implementation to translate Active Directory group names
      * to role names.  This implementation uses the {@link #groupRolesMap} to map group names to role names.
      *
      * @param groupNames the group names that apply to the current user.
      * @return a collection of roles that are implied by the given role names.
      */
     protected Collection<String> getRoleNamesForGroups(Collection<String> groupNames) {
         Set<String> roleNames = new HashSet<String>(groupNames.size());

         if (groupRolesMap != null) {
             for (String groupName : groupNames) {
                 String strRoleNames = groupRolesMap.get(groupName);
                 if (strRoleNames != null) {
                     for (String roleName : strRoleNames.split(ROLE_NAMES_DELIMETER)) {
                         logger.debug("User is member of group [{}] so adding role [{}]", groupName, roleName);
                         roleNames.add(roleName);
                     }
                 }else{
                     logger.warn("Did not find any group to role mappings for groupName: {}", groupName);
                 }
             }
         }
         return roleNames;
     }


}
