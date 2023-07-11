package com.server.global.auth.filter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.google.gson.Gson;
import com.server.domain.token.repository.RefreshTokenRepository;
import com.server.domain.token.service.RedisUtils;
import com.server.domain.token.service.RefreshTokenService;
import com.server.global.auth.error.AuthenticationError;
import com.server.global.auth.jwt.JwtTokenizer;
import com.server.global.auth.utils.AccessTokenRenewalUtil;
import com.server.global.auth.utils.Token;
import com.server.global.exception.CustomException;
import com.server.global.exception.ExceptionCode;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class JwtVerificationFilter extends OncePerRequestFilter {
    private final JwtTokenizer jwtTokenizer;
    private final AccessTokenRenewalUtil accessTokenRenewalUtil;
    private final RedisUtils redisUtils;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        try {
            String jws = request.getHeader("Authorization").replace("Bearer ", "");

            if(redisUtils.hasKeyBlackList(jws))
                throw new CustomException(ExceptionCode.LOGOUT_USER);

            Map<String, Object> claims = verifyJws(jws);
            setAuthenticationToContext(claims);
            filterChain.doFilter(request, response);
        } catch (ExpiredJwtException eje) {
            try {
                log.error("### 토큰이 만료됐습니다.");
                Token token = accessTokenRenewalUtil.renewAccessToken(request);

                jwtTokenizer.setHeaderAccessToken(response, token.getAccessToken());
                jwtTokenizer.setHeaderRefreshToken(response, token.getRefreshToken());

                Map<String, Object> claims = verifyJws(token.getAccessToken());
                setAuthenticationToContext(claims);
                filterChain.doFilter(request, response);
            } catch (CustomException ce) {
                log.error("### 리프레쉬 토큰을 찾을 수 없음");
                AuthenticationError.sendErrorResponse(response, ce);
            }
        } catch (MalformedJwtException mje) {
            log.error("### 올바르지 않은 토큰 형식입니다.");
            AuthenticationError.sendErrorResponse(response, new CustomException(ExceptionCode.TOKEN_FORMAT_INVALID));
        } catch (SignatureException se){
            log.error("### 토큰의 서명이 잘못 됐습니다. 변조 데이터일 가능성이 있습니다.");
            AuthenticationError.sendErrorResponse(response, new CustomException(ExceptionCode.SIGNATURE_INVALID));
        }
        catch (Exception e) {
            log.error("### 토큰 검증 오류 : " + e);
            AuthenticationError.sendErrorResponse(response, e);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String authorization = request.getHeader("Authorization");

        return authorization == null || !authorization.startsWith("Bearer");
    }

    private Map<String, Object> verifyJws(String jws) {
        String base64EncodedSecretKey = jwtTokenizer.encodeBase64SecretKey(jwtTokenizer.getSecretKey());
        Map<String, Object> claims = jwtTokenizer.getClaims(jws, base64EncodedSecretKey).getBody();

        return claims;
    }

    private void setAuthenticationToContext(Map<String, Object> claims) {
        List<String> roles = (List)claims.get("roles");
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList(roles.toArray(String[]::new));
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            claims.get("email"),
            claims.get("memberId"),
            authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
