package org.openhim.mediator.datatypes;

public class AssigningAuthority {
    private String assigningAuthority;
    private String assigningAuthorityId;

    public AssigningAuthority() {
    }

    public AssigningAuthority(String assigningAuthority, String assigningAuthorityId) {
        this.assigningAuthority = assigningAuthority;
        this.assigningAuthorityId = assigningAuthorityId;
    }

    public String getAssigningAuthority() {
        return assigningAuthority;
    }

    public void setAssigningAuthority(String assigningAuthority) {
        this.assigningAuthority = assigningAuthority;
    }

    public String getAssigningAuthorityId() {
        return assigningAuthorityId;
    }

    public void setAssigningAuthorityId(String assigningAuthorityId) {
        this.assigningAuthorityId = assigningAuthorityId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AssigningAuthority authority = (AssigningAuthority) o;

        if (assigningAuthority != null ? !assigningAuthority.equals(authority.assigningAuthority) : authority.assigningAuthority != null)
            return false;
        if (assigningAuthorityId != null ? !assigningAuthorityId.equals(authority.assigningAuthorityId) : authority.assigningAuthorityId != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = assigningAuthority != null ? assigningAuthority.hashCode() : 0;
        result = 31 * result + (assigningAuthorityId != null ? assigningAuthorityId.hashCode() : 0);
        return result;
    }
}
