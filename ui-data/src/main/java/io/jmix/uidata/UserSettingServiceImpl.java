/*
 * Copyright 2020 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.jmix.uidata;

import io.jmix.core.AccessManager;
import io.jmix.core.Metadata;
import io.jmix.core.UuidProvider;
import io.jmix.core.annotation.Internal;
import io.jmix.core.common.util.Preconditions;
import io.jmix.core.common.xmlparsing.Dom4jTools;
import io.jmix.core.entity.BaseUser;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.PermissionType;
import io.jmix.data.accesscontext.CrudEntityContext;
import io.jmix.ui.settings.UserSettingService;
import io.jmix.uidata.entity.UiSetting;
import io.jmix.uidata.entity.UiTablePresentation;
import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Nullable;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Internal
public class UserSettingServiceImpl implements UserSettingService {

    @Autowired
    protected CurrentAuthentication authentication;

    @Autowired
    protected Metadata metadata;

    @Autowired
    protected Dom4jTools dom4JTools;

    @Autowired
    protected AccessManager accessManager;

    @PersistenceContext
    protected EntityManager entityManager;

    protected TransactionTemplate transaction;

    @Autowired
    protected void setTransactionManager(PlatformTransactionManager transactionManager) {
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String loadSetting(String name) {
        Preconditions.checkNotNullArgument(name);

        String value = transaction.execute(status -> {
            UiSetting us = findUserSettings(name);
            return us == null ? null : us.getValue();
        });

        return value;
    }

    @Override
    public void saveSetting(String name, @Nullable String value) {
        Preconditions.checkNotNullArgument(name);

        transaction.executeWithoutResult(status -> {
            UiSetting us = findUserSettings(name);
            if (us == null) {
                us = metadata.create(UiSetting.class);
                us.setUserLogin(authentication.getUser().getUsername());
                us.setName(name);
                us.setValue(value);

                entityManager.persist(us);
            } else {
                us.setValue(value);
            }
        });
    }

    @Override
    public void deleteSettings(String name) {
        Preconditions.checkNotNullArgument(name);

        transaction.executeWithoutResult(status -> {
            UiSetting us = findUserSettings(name);
            if (us != null) {
                entityManager.remove(us);
            }
        });
    }

    @Override
    public void copySettings(BaseUser fromUser, BaseUser toUser) {
        Preconditions.checkNotNullArgument(fromUser);
        Preconditions.checkNotNullArgument(toUser);

        MetaClass metaClass = metadata.getClass(UiSetting.class);

        CrudEntityContext entityContext = new CrudEntityContext(metaClass);
        accessManager.applyRegisteredConstraints(entityContext);

        if (!entityContext.isCreatePermitted()) {
            throw new AccessDeniedException(PermissionType.ENTITY_OP, metaClass.getName());
        }

        transaction.executeWithoutResult(status -> {
            Query deleteSettingsQuery = entityManager.createQuery("delete from ui_Setting s where s.userLogin = ?1");
            deleteSettingsQuery.setParameter(1, toUser.getUsername());
            deleteSettingsQuery.executeUpdate();
        });

        Map<UUID, UiTablePresentation> presentationsMap = copyPresentations(fromUser, toUser);

        transaction.executeWithoutResult(status -> {
            TypedQuery<UiSetting> q = entityManager.
                    createQuery("select s from ui_Setting s where s.userLogin = ?1", UiSetting.class);
            q.setParameter(1, fromUser.getUsername());
            List<UiSetting> fromUserSettings = q.getResultList();

            for (UiSetting currSetting : fromUserSettings) {
                UiSetting newSetting = metadata.create(UiSetting.class);
                newSetting.setUserLogin(toUser.getUsername());
                newSetting.setName(currSetting.getName());

                try {
                    Document doc = dom4JTools.readDocument(currSetting.getValue());

                    List<Element> components = doc.getRootElement().element("components").elements("component");
                    for (Element component : components) {
                        Attribute presentationAttr = component.attribute("presentation");
                        if (presentationAttr != null) {
                            UUID presentationId = UuidProvider.fromString(presentationAttr.getValue());
                            UiTablePresentation newPresentation = presentationsMap.get(presentationId);
                            if (newPresentation != null) {
                                presentationAttr.setValue(newPresentation.getId().toString());
                            }
                        }
                    }

                    newSetting.setValue(dom4JTools.writeDocument(doc, true));
                } catch (Exception e) {
                    newSetting.setValue(currSetting.getValue());
                }
                entityManager.persist(newSetting);
            }
        });
    }

    @Override
    public void deleteScreenSettings(Set<String> screens) {
        transaction.executeWithoutResult(status -> {
            TypedQuery<UiSetting> selectQuery = entityManager.createQuery(
                    "select e from ui_Setting e where e.user.id = ?1",
                    UiSetting.class);
            selectQuery.setParameter(1, authentication.getUser().getUsername());

            List<UiSetting> userSettings = selectQuery.getResultList();

            for (UiSetting userSetting : userSettings) {
                if (screens.contains(userSetting.getName())) {
                    entityManager.remove(userSetting);
                }
            }
        });
    }

    @Nullable
    protected UiSetting findUserSettings(String name) {
        TypedQuery<UiSetting> q = entityManager.createQuery(
                "select s from ui_Setting s where s.userLogin = ?1 and s.name =?2",
                UiSetting.class);
        q.setParameter(1, authentication.getUser().getUsername());
        q.setParameter(2, name);

        List result = q.getResultList();
        if (result.isEmpty()) {
            return null;
        }

        return (UiSetting) result.get(0);
    }

    protected Map<UUID, UiTablePresentation> copyPresentations(BaseUser fromUser, BaseUser toUser) {
        Map<UUID, UiTablePresentation> resultMap = transaction.execute(status -> {
            // delete existing
            Query delete = entityManager.createQuery("delete from ui_TablePresentation p where p.userLogin = ?1");
            delete.setParameter(1, toUser.getUsername());
            delete.executeUpdate();

            // copy settings
            TypedQuery<UiTablePresentation> selectQuery = entityManager.createQuery(
                    "select p from ui_TablePresentation p where p.userLogin = ?1", UiTablePresentation.class);
            selectQuery.setParameter(1, fromUser.getUsername());
            List<UiTablePresentation> presentations = selectQuery.getResultList();

            Map<UUID, UiTablePresentation> presentationMap = new HashMap<>();
            for (UiTablePresentation presentation : presentations) {
                UiTablePresentation newPresentation = metadata.create(UiTablePresentation.class);
                newPresentation.setUserLogin(toUser.getUsername());
                newPresentation.setComponentId(presentation.getComponentId());
                newPresentation.setAutoSave(presentation.getAutoSave());
                newPresentation.setName(presentation.getName());
                newPresentation.setSettings(presentation.getSettings());
                presentationMap.put(presentation.getId(), newPresentation);
                entityManager.persist(newPresentation);
            }
            return presentationMap;
        });

        return resultMap == null ? Collections.emptyMap() : resultMap;
    }
}

