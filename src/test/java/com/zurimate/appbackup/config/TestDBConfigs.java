package com.zurimate.appbackup.config;

import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.utils.DBType;

public final class TestDBConfigs {
    private TestDBConfigs(){}
    public static DBConfig getMysqlConfig(){
        return new DBConfig(DBType.MYSQL,null,3310,"e2Hk46euYi8J","08NK3X%iio|=0*A0d","meru_brooks_smis",5);
    }
}
