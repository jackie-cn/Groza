package iot.technology.server.dao.device;

import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import iot.technology.server.common.data.*;
import iot.technology.server.common.data.device.DeviceSearchQuery;
import iot.technology.server.common.data.id.CustomerId;
import iot.technology.server.common.data.id.DeviceId;
import iot.technology.server.common.data.id.EntityId;
import iot.technology.server.common.data.id.TenantId;
import iot.technology.server.common.data.page.TextPageData;
import iot.technology.server.common.data.page.TextPageLink;
import iot.technology.server.common.data.relation.EntityRelation;
import iot.technology.server.common.data.relation.EntitySearchDirection;
import iot.technology.server.common.data.security.DeviceCredentials;
import iot.technology.server.common.data.security.DeviceCredentialsType;
import iot.technology.server.dao.DaoUtil;
import iot.technology.server.dao.customer.CustomerDao;
import iot.technology.server.dao.entity.AbstractEntityService;
import iot.technology.server.dao.exception.DataValidationException;
import iot.technology.server.dao.service.DataValidator;
import iot.technology.server.dao.service.PaginatedRemover;
import iot.technology.server.dao.service.Validator;
import iot.technology.server.dao.tenant.TenantDao;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

import static iot.technology.server.common.data.CacheConstants.DEVICE_CACHE;
import static iot.technology.server.dao.model.ModelConstants.NULL_UUID;
import static iot.technology.server.dao.service.Validator.validateId;

/**
 * @author james mu
 * @date 18-12-25 上午9:32
 */
@Service
@Slf4j
public class DeviceServiceImpl extends AbstractEntityService implements DeviceService{

    public static final String INCORRECT_TENANT_ID = "Incorrect tenantId ";
    public static final String INCORRECT_PAGE_LINK = "Incorrect page link ";
    public static final String INCORRECT_CUSTOMER_ID = "Incorrect customerId ";
    public static final String INCORRECT_DEVICE_ID = "Incorrect deviceId ";

    @Autowired
    private DeviceDao deviceDao;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private CustomerDao customerDao;

    @Autowired
    private DeviceCredentialsService deviceCredentialsService;

    @Autowired
    private CacheManager cacheManager;


    @Override
    public Device findDeviceById(DeviceId deviceId) {
        log.trace("Executing findDeviceById [{}]", deviceId);
        validateId(deviceId , INCORRECT_DEVICE_ID + deviceId);
        return deviceDao.findById(deviceId.getId());
    }

    @Override
    public ListenableFuture<Device> findDeviceByIdAsync(DeviceId deviceId) {
        log.trace("Executing findDeviceById", deviceId);
        validateId(deviceId, INCORRECT_DEVICE_ID + deviceId);
        return deviceDao.findByIdAsync(deviceId.getId());
    }

    @Cacheable(cacheNames = DEVICE_CACHE, key = "{#tenantId, #name}")
    @Override
    public Device findDeviceByTenantIdAndName(TenantId tenantId, String name) {
        log.trace("Executing findDeviceByTenantIdAndName [{}][{}]", tenantId, name);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        Optional<Device> deviceOpt = deviceDao.findDeviceByTenantIdAndName(tenantId.getId(), name);
        return deviceOpt.orElse(null);
    }

    @CacheEvict(cacheNames = DEVICE_CACHE, key = "{#device.tenantId, #device.name}")
    @Override
    public Device saveDevice(Device device) {
        log.trace("Executing saveDevice [{}]", device);
        deviceValidator.validate(device);
        Device savedDevice = deviceDao.save(device);
        if (device.getId() == null){
            DeviceCredentials deviceCredentials = new DeviceCredentials();
            deviceCredentials.setDeviceId(new DeviceId(savedDevice.getUuidId()));
            deviceCredentials.setCredentialsType(DeviceCredentialsType.ACCESS_TOKEN);
            deviceCredentials.setCredentialsId(RandomStringUtils.randomAlphanumeric(20));
            deviceCredentialsService.createDeviceCredentials(deviceCredentials);
        }
        return savedDevice;
    }

    @Override
    public Device assignDeviceToCustomer(DeviceId deviceId, CustomerId customerId) {
        Device device = findDeviceById(deviceId);
        device.setCustomerId(customerId);
        return saveDevice(device);
    }

    @Override
    public Device unassignDeviceFromCustomer(DeviceId deviceId) {
        Device device = findDeviceById(deviceId);
        device.setCustomerId(null);
        return saveDevice(device);
    }

    @Override
    public void deleteDevice(DeviceId deviceId) {
        log.trace("Executing deleteDevice [{}]", deviceId);
        Cache cache = cacheManager.getCache(DEVICE_CACHE);
        validateId(deviceId, INCORRECT_DEVICE_ID + deviceId);
        DeviceCredentials deviceCredentials = deviceCredentialsService.findDeviceCredentialsByDeviceId(deviceId);
        if (deviceCredentials != null) {
            deviceCredentialsService.deleteDeviceCredentials(deviceCredentials);
        }
        deleteEntityRelations(deviceId);
        Device device = deviceDao.findById(deviceId.getId());
        List<Object> list = new ArrayList<>();
        list.add(device.getTenantId());
        list.add(device.getName());
        cache.evict(list);
        deviceDao.removeById(deviceId.getId());

    }

    @Override
    public TextPageData<Device> findDevicesByTenantId(TenantId tenantId, TextPageLink pageLink) {
        log.trace("Executing findDevicesByTenantId, tenantID [{}], pageLink [{}]", tenantId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        Validator.validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Device> devices = deviceDao.findDevicesByTenantId(tenantId.getId(), pageLink);
        return new TextPageData<>(devices, pageLink);

    }

    @Override
    public TextPageData<Device> findDevicesByTenantIdAndType(TenantId tenantId, String type, TextPageLink pageLink) {
        log.trace("Executing findDevicesByTenantIdAndType, tenantId [{}], type [{}], pageLink [{}]", tenantId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        Validator.validateString(type, "Incorrect type " + type);
        Validator.validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Device> devices = deviceDao.findDevicesByTenantIdAndType(tenantId.getId(), type, pageLink);
        return new TextPageData<>(devices, pageLink);
    }

    @Override
    public ListenableFuture<List<Device>> findDevicesByTenantIdAndIdsAsync(TenantId tenantId, List<DeviceId> deviceIds) {
        log.trace("Executing findDevicesByTenantIdAndIdsAsync, tenantId [{}], deviceIds [{}]", tenantId, deviceIds);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        Validator.validateIds(deviceIds, "Incorrect deviceIds " + deviceIds);
        return deviceDao.findDevicesByTenantIdAndIdsAsync(tenantId.getId(), DaoUtil.toUUIDs(deviceIds));
    }

    @Override
    public void deleteDevicesByTenantId(TenantId tenantId) {
        log.trace("Executing deleteDevicesByTenantId, tenantId [{}]", tenantId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        tenantDevicesRemover.removeEntities(tenantId);
    }

    @Override
    public TextPageData<Device> findDevicesByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId, TextPageLink pageLink) {
        log.trace("Executing findDevicesByTenantIdAndCustomerId, tenantId [{}], customerId[{}], pageLink [{}]", tenantId, customerId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        Validator.validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Device> devices = deviceDao.findDevicesByTenantIdAndCustomerId(tenantId.getId(), customerId.getId(), pageLink);
        return new TextPageData<>(devices, pageLink);
    }

    @Override
    public TextPageData<Device> findDevicesByTenantIdAndCustomerIdAndType(TenantId tenantId, CustomerId customerId, String type, TextPageLink pageLink) {
        log.trace("Executing findDevicesByTenantIdAndCustomerIdAndType, tenantId [{}], customerId [{}], type [{}], pageLink [{}]", tenantId,
                customerId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        Validator.validateString(type, "Incorrect type " + type);
        Validator.validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Device> devices = deviceDao.findDevicesByTenantIdAndCustomerIdAndType(tenantId.getId(), customerId.getId(), type, pageLink);
        return new TextPageData<>(devices, pageLink);
    }

    @Override
    public ListenableFuture<List<Device>> findDevicesByTenantIdCustomerIdAndIdsAsync(TenantId tenantId, CustomerId customerId, List<DeviceId> deviceIds) {
        log.trace("Executing findDevicesByTenantIdCustomerIdAndIdsAsync, tenantId [{}], customerId [{}], deviceIds [{}]", tenantId, customerId, deviceIds);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        Validator.validateIds(deviceIds, "Incorrect deviceIds " + deviceIds);
        return deviceDao.findDevicesByTenantIdCustomerIdAndIdsAsync(tenantId.getId(),
                customerId.getId(), DaoUtil.toUUIDs(deviceIds));
    }

    @Override
    public void unassignCustomerDevices(TenantId tenantId, CustomerId customerId) {
        log.trace("Executing unassignCustomerDevices, tenantId [{}], customerId [{}]", tenantId, customerId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        new CustomerDevicesUnassigner(tenantId).removeEntities(customerId);
    }

    @Override
    public ListenableFuture<List<Device>> findDevicesByQuery(DeviceSearchQuery query) {
        ListenableFuture<List<EntityRelation>> relations = relationService.findByQuery(query.toEntitySearchQuery());
        ListenableFuture<List<Device>> devices = Futures.transformAsync(relations, r -> {
            EntitySearchDirection direction = query.toEntitySearchQuery().getParameters().getDirection();
            List<ListenableFuture<Device>> futures = new ArrayList<>();
            for (EntityRelation relation : r) {
                EntityId entityId = direction == EntitySearchDirection.FROM ? relation.getTo() : relation.getFrom();
                if (entityId.getEntityType() == EntityType.DEVICE) {
                    futures.add(findDeviceByIdAsync(new DeviceId(entityId.getId())));
                }
            }
            return Futures.successfulAsList(futures);
        });

        devices = Futures.transform(devices, new Function<List<Device>, List<Device>>() {
            @Nullable
            @Override
            public List<Device> apply(@Nullable List<Device> deviceList) {
                return deviceList == null ? Collections.emptyList() : deviceList.stream().filter(device -> query.getDeviceTypes().contains(device.getType())).collect(Collectors.toList());
            }
        });

        return devices;
    }

    @Override
    public ListenableFuture<List<EntitySubtype>> findDeviceTypesByTenantId(TenantId tenantId) {
        log.trace("Executing findDeviceTypesByTenantId, tenantId [{}]", tenantId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        ListenableFuture<List<EntitySubtype>> tenantDeviceTypes = deviceDao.findTenantDeviceTypesAsync(tenantId.getId());
        return Futures.transform(tenantDeviceTypes,
                (Function<List<EntitySubtype>, List<EntitySubtype>>) deviceTypes -> {
                    deviceTypes.sort(Comparator.comparing(EntitySubtype::getType));
                    return deviceTypes;
                });
    }

    private DataValidator<Device> deviceValidator =

            new DataValidator<Device>() {

                @Override
                protected void validateDataImpl(Device device) {
                    if (StringUtils.isEmpty(device.getType())) {
                        throw new DataValidationException("Device type should be specified!");
                    }
                    if (StringUtils.isEmpty(device.getName())) {
                        throw new DataValidationException("Device name should be specified!");
                    }
                    if (device.getTenantId() == null) {
                        throw new DataValidationException("Device should be assigned to tenant!");
                    } else {
                        Tenant tenant = tenantDao.findById(device.getTenantId().getId());
                        if (tenant == null) {
                            throw new DataValidationException("Device is referencing to non-existent tenant!");
                        }
                    }
                    if (device.getCustomerId() == null) {
                        device.setCustomerId(new CustomerId(NULL_UUID));
                    } else if (!device.getCustomerId().getId().equals(NULL_UUID)) {
                        Customer customer = customerDao.findById(device.getCustomerId().getId());
                        if (customer == null) {
                            throw new DataValidationException("Can't assign device to non-existent customer!");
                        }
                        if (!customer.getTenantId().getId().equals(device.getTenantId().getId())) {
                            throw new DataValidationException("Can't assign device to customer from different tenant!");
                        }
                    }
                }

                @Override
                protected void validateCreate(Device device) {
                    deviceDao.findDeviceByTenantIdAndName(device.getTenantId().getId(), device.getName()).ifPresent(
                            d -> {
                                throw new DataValidationException("Device with such name already exists!");
                            }
                    );
                }

                @Override
                protected void validateUpdate(Device device) {
                    deviceDao.findDeviceByTenantIdAndName(device.getTenantId().getId(), device.getName()).ifPresent(
                            d -> {
                                if (!d.getUuidId().equals(device.getUuidId())) {
                                    throw new DataValidationException("Device with such name already exists!");
                                }
                            }
                    );
                }

            };


    private PaginatedRemover<TenantId, Device> tenantDevicesRemover =
            new PaginatedRemover<TenantId, Device>() {
                @Override
                protected List<Device> findEntities(TenantId id, TextPageLink pageLink) {
                    return deviceDao.findDevicesByTenantId(id.getId(), pageLink);
                }

                @Override
                protected void removeEntity(Device entity) {
                    deleteDevice(new DeviceId(entity.getUuidId()));
                }
            };

    private class CustomerDevicesUnassigner extends PaginatedRemover<CustomerId, Device> {

        private TenantId tenantId;

        CustomerDevicesUnassigner(TenantId tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        protected List<Device> findEntities(CustomerId id, TextPageLink pageLink) {
            return deviceDao.findDevicesByTenantIdAndCustomerId(tenantId.getId(), id.getId(), pageLink);
        }

        @Override
        protected void removeEntity(Device entity) {
            unassignDeviceFromCustomer(new DeviceId(entity.getUuidId()));
        }
    }
}