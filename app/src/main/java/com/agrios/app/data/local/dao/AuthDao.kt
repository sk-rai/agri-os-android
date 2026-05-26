package com.agrios.app.data.local.dao

import androidx.room.*
import com.agrios.app.data.local.entity.AuthStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuthDao {
    @Query("SELECT * FROM auth_state WHERE id = 1")
    fun observeAuthState(): Flow<AuthStateEntity?>

    @Query("SELECT * FROM auth_state WHERE id = 1")
    suspend fun getAuthState(): AuthStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAuthState(state: AuthStateEntity)

    @Query("DELETE FROM auth_state")
    suspend fun clearAuth()
}
