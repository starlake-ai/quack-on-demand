Add user and group and role management.
Move tenant identity management and ACL management here.

A role is a set of permissions on tables
A user can belong to one of multi groups
A role and a group are scoped and unique per tenant

A user can be a member of  multiple groups
A user can have multiple roles and the union of all his role make up his permissions

for each user when :
(tenant, pool) == (null, null) he is a superuser
(tenant, pool) = (value not null, null) he can access all pools of this tenant
(tenant, pool) == (null, valuenotnull) impossible


