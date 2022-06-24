package com.pingcap.ecommerce.service;

import com.pingcap.ecommerce.dao.snowflake.SnowflakeHotItemMapper;
import com.pingcap.ecommerce.dao.snowflake.SnowflakeSchemaMapper;
import com.pingcap.ecommerce.dao.snowflake.SnowflakeUserLabelMapper;
import com.pingcap.ecommerce.dao.tidb.*;
import com.pingcap.ecommerce.model.*;
import com.pingcap.ecommerce.util.job.JobManager;
import com.pingcap.ecommerce.vo.OrderTotalVO;
import com.pingcap.ecommerce.vo.OrderTypeTotalVO;
import com.pingcap.ecommerce.vo.PageResultVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
@AllArgsConstructor
public class DataService {

    private final SnowflakeSchemaMapper snowflakeSchemaMapper;

    private final SnowflakeUserLabelMapper snowflakeUserLabelMapper;

    private final SnowflakeHotItemMapper snowflakeHotItemMapper;

    private final UserMapper userMapper;

    private final UserLabelMapper userLabelMapper;

    private final HotItemMapper hotItemMapper;

    private final OrderMapper orderMapper;

    private final OrderSeriesMapper orderSeriesMapper;

    private final JobManager jobManager;

    public void calcTodayOrderTotalAndAmount() {
        Date now = new Date();
        OrderTotalVO orderTotalAndAmount = orderMapper.getOrderTotalAndAmount();
        OrderSeries orderSeries = new OrderSeries();
        orderSeries.setType("ALL");
        orderSeries.setTs(now);
        orderSeries.setAmount(orderTotalAndAmount.getTotalAmount());
        orderSeries.setTotal(orderTotalAndAmount.getTotalCount());
        orderSeriesMapper.insertOrderSeries(orderSeries);
    }

    public void calcTodayOrderTotalAndAmountGroupByType() {
        Date now = new Date();
        List<OrderTypeTotalVO> orderTotalAndAmountByType = orderMapper.getOrderTotalAndAmountByType();
        List<OrderSeries> seriesList = new ArrayList<>();

        for (OrderTypeTotalVO vo : orderTotalAndAmountByType) {
            OrderSeries series = new OrderSeries();
            series.setTs(now);
            series.setType(vo.getItemType());
            series.setTotal(vo.getTotalCount());
            series.setAmount(vo.getTotalAmount());
            seriesList.add(series);
        }

        if (seriesList.size() > 0) {
            orderSeriesMapper.insertOrderSeriesList(seriesList);
        }
    }

    /**
     * User labels.
    */
    public BigInteger countUserLabels() {
        return snowflakeUserLabelMapper.countUserLabels();
    }

    public void calcUserLabels() {
        log.info("Calc user labels on Snowflake.");
        snowflakeUserLabelMapper.calcUserLabels();
    }

    public void pullBackUserLabelsToTiDB(JobInstance jobInstance) {
        List<UserLabel> userLabels;
        long pageNum = 1;
        long pageSize = 2000;

        log.info("Pulling back user labels from Snowflake to TiDB.");
        snowflakeSchemaMapper.useJSONResultFormat();

        do {
            long offset = (pageNum - 1) * pageSize;
            userLabels = snowflakeUserLabelMapper.getUserLabels(pageSize, offset);
            log.info("Pulling back user labels from Snowflake to TiDB, page {}.", pageNum);
            int insertRows = bulkInsertOverwriteUserLabels(userLabels);
            pageNum++;

            jobInstance = jobManager.updateJobInstanceProcess(jobInstance.getId(), insertRows);
        } while (userLabels.size() > 0);

        log.info("Successfully pull back user labels from Snowflake to TiDB!");
    }

    @Transactional
    public int bulkInsertOverwriteUserLabels(List<UserLabel> userLabels) {
        if (userLabels.isEmpty()) return 0;
        List<String> userIds = userLabels.stream().map(UserLabel::getUserId).toList();
        userLabelMapper.bulkDeleteUserLabels(userIds);
        return userLabelMapper.batchInsertUserLabels(userLabels);
    }

    /**
     * Items.
     */

    @Transactional("SecondaryTransactionManager")
    public void calcHighLabelItems() {
        log.info("Calc high labels items on Snowflake.");
        snowflakeHotItemMapper.deleteHighLabelItems();
        snowflakeHotItemMapper.calcHighLabelItems();
    }

    @Transactional("SecondaryTransactionManager")
    public void calcLowLabelItems() {
        log.info("Calc low labels items on Snowflake.");
        snowflakeHotItemMapper.deleteLowLabelItems();
        snowflakeHotItemMapper.calcLowLabelItems();
    }

    public void pullBackHotItemsToTiDB() {
        List<HotItem> hotItems;
        long pageNum = 1;
        long pageSize = 1000;

        log.info("Pulling back hot items from Snowflake to TiDB.");
        snowflakeSchemaMapper.useJSONResultFormat();

        do {
            long offset = (pageNum - 1) * pageSize;
            hotItems = snowflakeHotItemMapper.getHotItems(pageSize, offset);
            bulkInsertOverwriteHotItems(hotItems);
            pageNum++;
        } while (hotItems.size() > 0);

        log.info("Successfully pull back hot items from Snowflake to TiDB!");
    }

    @Transactional
    public void bulkInsertOverwriteHotItems(List<HotItem> hotItems) {
        if (hotItems.isEmpty()) return;
        List<Long> hotItemIds = hotItems.stream().map(HotItem::getItemId).toList();
        hotItemMapper.bulkDeleteHotItems(hotItemIds);
        hotItemMapper.batchInsertHotItems(hotItems);
    }

    /**
     * Recommend the matched items according the label of items and current user.
     */
    public PageResultVO<HotItem> getRecommendedHotItems(String userId, Pageable pageable) {
        List<HotItem> hotItems = hotItemMapper.getRecommendedHotItems(userId, pageable);
        List<String> userIds = hotItems.stream().map(HotItem::getUserId).toList();

        if (!userIds.isEmpty()) {
            List<User> userByIds = userMapper.getUserByIds(userIds);
            hotItems.forEach(item -> userByIds.stream()
                    .filter(u -> u.getId().equals(item.getUserId())).findFirst()
                    .ifPresent(user -> item.setUserName(user.getUsername()))
            );
        }

        return PageResultVO.of(hotItems, 10000, pageable.getPageNumber(), pageable.getPageSize());
    }

    public List<HotItem> getHighLabelItems() {
        return hotItemMapper.getHighLabelItems();
    }

    public List<HotItem> getLowLabelItems() {
        return hotItemMapper.getLowLabelItems();
    }

}
