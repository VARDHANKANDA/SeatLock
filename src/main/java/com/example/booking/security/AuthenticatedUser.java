package com.example.booking.security;

import com.example.booking.entity.Role;

public record AuthenticatedUser(Long id, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
