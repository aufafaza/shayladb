package mdbms.api;

import mdbms.models.schema.Statistic;

public interface IStatisticsProvider{
    Statistic getStatistics(String table);
}