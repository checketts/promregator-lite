package com.github.promregator.auth

import org.springframework.http.HttpHeaders

interface AuthenticationEnricher {
    fun enrichWithAuthentication(headers: HttpHeaders?)
}