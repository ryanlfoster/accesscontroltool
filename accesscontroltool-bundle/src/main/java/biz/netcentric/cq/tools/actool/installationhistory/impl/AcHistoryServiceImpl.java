/*
 * (C) Copyright 2015 Netcentric AG.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.cq.tools.actool.installationhistory.impl;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.jcr.api.SlingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import biz.netcentric.cq.tools.actool.comparators.TimestampPropertyComparator;
import biz.netcentric.cq.tools.actool.installationhistory.AcHistoryService;
import biz.netcentric.cq.tools.actool.installationhistory.AcInstallationHistoryPojo;

import com.day.cq.commons.jcr.JcrUtil;

@Service
@Component(metatype = true, label = "AC History Service", immediate = true, description = "Service that writes & fetches Ac installation histories")
@Properties({ @Property(label = "ACL number of histories to save", name = "AceService.nrOfSavedHistories", value = "5")

})
public class AcHistoryServiceImpl implements AcHistoryService {
    private static final String INSTALLED_CONFIGS_NODE_NAME = "installedConfigs";
    private static final int NR_OF_HISTORIES_TO_SAVE_DEFAULT = 5;
    private static final Logger LOG = LoggerFactory
            .getLogger(AcHistoryServiceImpl.class);
    private final static String PURGE_HISTORY_NODE_NAME_PREFIX = "purgeACL";
    private int nrOfSavedHistories;

    @Reference
    private SlingRepository repository;

    @Activate
    public void activate(@SuppressWarnings("rawtypes") final Map properties)
            throws Exception {
        this.nrOfSavedHistories = PropertiesUtil.toInteger(
                properties.get("AceService.nrOfSavedHistories"),
                NR_OF_HISTORIES_TO_SAVE_DEFAULT);

    }

    @Override
    public void persistHistory(AcInstallationHistoryPojo history,
            final String configurationRootPath) {
        Session session = null;
        try {
            try {
                session = repository.loginAdministrative(null);
                Node historyNode = HistoryUtils.persistHistory(session,
                        history, this.nrOfSavedHistories);
                session.save();
                if (history.isSuccess()) {
                    Node configurationRootNode = session
                            .getNode(configurationRootPath);
                    if (configurationRootNode != null) {
                        persistInstalledConfigurations(historyNode,
                                configurationRootNode, history);
                        session.save();
                    } else {
                        String message = "Couldn't find configuration root Node under path: "
                                + configurationRootPath;
                        LOG.error(message);
                        history.addWarning(message);
                    }
                }
            } catch (RepositoryException e) {
                LOG.error("RepositoryException: ", e);
            }
        } finally {
            if (session != null) {
                session.logout();
            }
        }
    }

    @Override
    public String[] getInstallationLogPaths() {
        Session session = null;
        try {
            session = repository.loginAdministrative(null);
            return HistoryUtils.getHistoryInfos(session);
        } catch (RepositoryException e) {
            LOG.error("RepositoryException: ", e);
        } finally {
            if (session != null) {
                session.logout();
            }
        }
        return null;
    }

    @Override
    public String getLogHtml(Session session, String path) {
        return HistoryUtils.getLogHtml(session, path);
    }

    @Override
    public String getLogTxt(Session session, String path) {
        return HistoryUtils.getLogTxt(session, path);
    }

    @Override
    public String getLastInstallationHistory() {
        Session session = null;
        String history = "";
        try {
            session = repository.loginAdministrative(null);

            Node statisticsRootNode = HistoryUtils
                    .getAcHistoryRootNode(session);
            NodeIterator it = statisticsRootNode.getNodes();

            if (it.hasNext()) {
                Node lastHistoryNode = it.nextNode();

                if (lastHistoryNode != null) {
                    history = getLogHtml(session, lastHistoryNode.getName());
                }
            } else {
                history = "no history found!";
            }
        } catch (RepositoryException e) {
            LOG.error("RepositoryException: ", e);
        } finally {
            if (session != null) {
                session.logout();
            }
        }
        return history;
    }

    public void persistInstalledConfigurations(final Node historyNode,
            final Node configurationRootNode, AcInstallationHistoryPojo history) {

        try {
            JcrUtil.copy(configurationRootNode, historyNode,
                    INSTALLED_CONFIGS_NODE_NAME);
        } catch (RepositoryException e) {
            String message = e.toString();
            history.setException(e.toString());
            LOG.error("Exception: ", e);
        }

        try {
            history.addMessage("saved installed configuration files under : "
                    + historyNode.getPath() + "/" + INSTALLED_CONFIGS_NODE_NAME);
        } catch (RepositoryException e) {
            LOG.error("Exception: ", e);
        }

    }

    public String showHistory(int n) {
        Session session = null;
        String history = "";
        try {
            session = repository.loginAdministrative(null);

            Node statisticsRootNode = HistoryUtils
                    .getAcHistoryRootNode(session);
            NodeIterator it = statisticsRootNode.getNodes();
            int cnt = 1;

            while (it.hasNext()) {
                Node historyNode = it.nextNode();

                if (historyNode != null && cnt == n) {
                    history = getLogTxt(session, historyNode.getName());
                }
                cnt++;
            }
        } catch (RepositoryException e) {
            LOG.error("RepositoryException: ", e);
        } finally {
            if (session != null) {
                session.logout();
            }
        }
        return history;
    }

    @Override
    public void persistAcePurgeHistory(AcInstallationHistoryPojo history) {
        Session session = null;

        try {
            session = repository.loginAdministrative(null);
            Node acHistoryRootNode = HistoryUtils.getAcHistoryRootNode(session);
            NodeIterator nodeIterator = acHistoryRootNode.getNodes();
            Set<Node> historyNodes = new TreeSet<Node>(
                    new TimestampPropertyComparator());
            Node newestHistoryNode = null;
            while (nodeIterator.hasNext()) {
                historyNodes.add(nodeIterator.nextNode());
            }
            if (!historyNodes.isEmpty()) {
                newestHistoryNode = historyNodes.iterator().next();
                persistPurgeAceHistory(session, history, newestHistoryNode);
                session.save();
            }

        } catch (RepositoryException e) {
            LOG.error("Exception: ", e);
        } finally {
            if (session != null) {
                session.logout();
            }
        }
    }

    private static Node persistPurgeAceHistory(final Session session,
            AcInstallationHistoryPojo history, final Node historyNode)
            throws RepositoryException {

        Node purgeHistoryNode = historyNode.addNode(
                "purge_" + System.currentTimeMillis(),
                HistoryUtils.NODETYPE_NT_UNSTRUCTURED);

        // if there is already a purge history node, order the new one before so
        // the newest one is always on top
        NodeIterator nodeIt = historyNode.getNodes();
        Node previousPurgeNode = null;
        while (nodeIt.hasNext()) {
            Node currNode = nodeIt.nextNode();
            // get previous purgeHistory node
            if (currNode.getName().contains("purge_")) {
                previousPurgeNode = currNode;
                break;
            }
        }

        if (previousPurgeNode != null) {
            historyNode.orderBefore(purgeHistoryNode.getName(),
                    previousPurgeNode.getName());
        }

        String message = "saved history in node: " + purgeHistoryNode.getPath();
        history.addMessage(message);
        LOG.info(message);
        HistoryUtils.setHistoryNodeProperties(purgeHistoryNode, history);
        return historyNode;
    }
}
