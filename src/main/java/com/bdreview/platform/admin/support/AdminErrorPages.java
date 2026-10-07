package com.bdreview.platform.admin.support;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.FeatureDisabledException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.health.RecentErrors;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;
import java.util.UUID;

/**
 * Admin panel error pages. Two paths lead here:
 * <ul>
 *   <li>an exception thrown by an admin controller — handled by {@link Handler} before the JSON
 *       {@code GlobalExceptionHandler} sees it;</li>
 *   <li>a failure after the handler (e.g. while rendering a template), which the container
 *       forwards to {@code /error} — {@link #resolveErrorView} turns that into the same page for
 *       {@code /admin/**} URLs.</li>
 * </ul>
 * Unexpected errors get a short request id that is shown on the page and logged with the stack
 * trace (and kept in System → Health's recent errors), so a screenshot is enough to find it.
 */
@Component
public class AdminErrorPages implements ErrorViewResolver {

    private static final Logger log = LoggerFactory.getLogger(AdminErrorPages.class);

    private final RecentErrors recentErrors;

    public AdminErrorPages(RecentErrors recentErrors) {
        this.recentErrors = recentErrors;
    }

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (!(uri instanceof String path) || !path.startsWith("/admin")) {
            return null;
        }
        Throwable error = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (error == null) {
            error = (Throwable) request.getAttribute(DefaultErrorAttributes.class.getName() + ".ERROR");
        }
        String requestId = null;
        if (status.is5xxServerError()) {
            requestId = newRequestId();
            log.error("Admin request {} failed: {} {}", requestId, request.getMethod(), path, error);
            if (error != null) {
                recentErrors.record(request.getMethod(), path + " [" + requestId + "]", error);
            }
        }
        return page(status, requestId, null, path);
    }

    static ModelAndView page(HttpStatus status, String requestId, String message, String path) {
        ModelAndView mav = new ModelAndView("admin/error");
        mav.setStatus(status);
        mav.addObject("status", status.value());
        mav.addObject("reason", status.getReasonPhrase());
        mav.addObject("requestId", requestId);
        mav.addObject("message", message);
        mav.addObject("path", path);
        return mav;
    }

    static String newRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Exceptions thrown inside admin controllers → the admin error page with the right status. */
    @ControllerAdvice(basePackages = "com.bdreview.platform.admin.controller")
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class Handler {

        private final RecentErrors recentErrors;

        public Handler(RecentErrors recentErrors) {
            this.recentErrors = recentErrors;
        }

        @ExceptionHandler({ResourceNotFoundException.class, FeatureDisabledException.class})
        public ModelAndView notFound(RuntimeException ex, HttpServletRequest req) {
            return page(HttpStatus.NOT_FOUND, null, ex.getMessage(), req.getRequestURI());
        }

        @ExceptionHandler({AccessDeniedException.class, ForbiddenException.class})
        public ModelAndView forbidden(RuntimeException ex, HttpServletRequest req) {
            return page(HttpStatus.FORBIDDEN, null, "Your admin role doesn't include this section.", req.getRequestURI());
        }

        @ExceptionHandler({BadRequestException.class, MethodArgumentTypeMismatchException.class,
                MissingServletRequestParameterException.class, IllegalArgumentException.class})
        public ModelAndView badRequest(Exception ex, HttpServletRequest req) {
            String message = ex instanceof BadRequestException ? ex.getMessage() : "That link or form had an invalid value.";
            return page(HttpStatus.BAD_REQUEST, null, message, req.getRequestURI());
        }

        @ExceptionHandler(Exception.class)
        public ModelAndView unexpected(Exception ex, HttpServletRequest req, HttpServletResponse res) {
            String requestId = newRequestId();
            log.error("Admin request {} failed: {} {}", requestId, req.getMethod(), req.getRequestURI(), ex);
            recentErrors.record(req.getMethod(), req.getRequestURI() + " [" + requestId + "]", ex);
            return page(HttpStatus.INTERNAL_SERVER_ERROR, requestId, null, req.getRequestURI());
        }
    }
}
