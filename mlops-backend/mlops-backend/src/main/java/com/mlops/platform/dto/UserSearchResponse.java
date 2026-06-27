package com.mlops.platform.dto;

import com.mlops.platform.entity.User;

public class UserSearchResponse {

    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String fullName;

    public UserSearchResponse() {
    }

    public static UserSearchResponse fromEntity(User user) {
        UserSearchResponse response = new UserSearchResponse();

        response.setId(user.getId());
        response.setFirstName(user.getFirstName());
        response.setLastName(user.getLastName());
        response.setEmail(user.getEmail());

        String fullName = ((user.getFirstName() == null ? "" : user.getFirstName()) + " " +
                (user.getLastName() == null ? "" : user.getLastName())).trim();

        response.setFullName(fullName.isBlank() ? user.getEmail() : fullName);

        return response;
    }

    public Long getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }
}