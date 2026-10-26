package com.madsports.config;

import com.madsports.account.Product;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * El mismo usuario puede tener una cuenta en cada producto: en el ingreso ({@code POST /api/login}), el usuario que
 * escribió la persona pasa a ser el de su producto ("juan" desde RinkReports es "REPORTS:juan"), que es como lo busca
 * el ingreso, como cuenta los intentos fallidos {@link AttemptLimitFilter} y como queda en la sesión. Sin el parámetro
 * {@code product} queda como vino.
 */
public class LoginProductFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        String username = req.getParameter("username");
        Product product = null;
        if ("POST".equals(req.getMethod()) && "/api/login".equals(path) && username != null && !username.isBlank()) {
            try {
                product = Product.parse(req.getParameter("product"));
            } catch (IllegalArgumentException e) {
                // Producto desconocido: el ingreso sigue con el usuario como vino.
            }
        }
        if (product == null) {
            chain.doFilter(req, res);
            return;
        }
        String principal = product.principal(username.trim().toLowerCase(Locale.ROOT));
        chain.doFilter(new HttpServletRequestWrapper(req) {
            @Override
            public String getParameter(String name) {
                return "username".equals(name) ? principal : super.getParameter(name);
            }
        }, res);
    }
}
