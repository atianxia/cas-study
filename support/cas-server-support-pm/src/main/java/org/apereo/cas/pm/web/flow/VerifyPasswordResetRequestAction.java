package org.apereo.cas.pm.web.flow;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.pm.PasswordManagementService;
import org.apereo.cas.web.support.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.webflow.action.AbstractAction;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.execution.RequestContext;

import javax.servlet.http.HttpServletRequest;
import java.util.Collection;

import static org.apereo.cas.pm.web.flow.SendPasswordResetInstructionsAction.PARAMETER_NAME_TOKEN;

/**
 * This is {@link VerifyPasswordResetRequestAction}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
public class VerifyPasswordResetRequestAction extends AbstractAction {
    private static final Logger LOGGER = LoggerFactory.getLogger(VerifyPasswordResetRequestAction.class);
    
    @Autowired
    private CasConfigurationProperties casProperties;

    private PasswordManagementService passwordManagementService;
    
    public VerifyPasswordResetRequestAction(final PasswordManagementService passwordManagementService) {
        this.passwordManagementService = passwordManagementService;
    }

    @Override
    protected Event doExecute(final RequestContext requestContext) throws Exception {
        final HttpServletRequest request = WebUtils.getHttpServletRequest(requestContext);
        final String token = request.getParameter(PARAMETER_NAME_TOKEN);
        
        if (StringUtils.isBlank(token)) {
            LOGGER.error("Password reset token is missing");
            return error();
        }
        
        final String username = passwordManagementService.parseToken(token);
        if (StringUtils.isBlank(username)) {
            LOGGER.error("Password reset token could not be verified");
            return error();
        }
        final Collection<String> questions =
                Lists.newArrayList(passwordManagementService.getSecurityQuestions(username).keySet());
        if (questions.isEmpty()) {
            LOGGER.error("No security questions could be found for " + username);
            return error();
        }
        requestContext.getFlowScope().put("token", token);
        requestContext.getFlowScope().put("username", username);
        requestContext.getFlowScope().put("questions", questions);
        return success();
    }
}
